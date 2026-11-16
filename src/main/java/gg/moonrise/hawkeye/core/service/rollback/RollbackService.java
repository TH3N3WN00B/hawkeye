package gg.moonrise.hawkeye.core.service.rollback;

import gg.moonrise.hawkeye.config.HawkeyeConfigProvider;
import gg.moonrise.hawkeye.config.file.RollbackSettings;
import gg.moonrise.hawkeye.core.queue.EventWriter;
import gg.moonrise.hawkeye.core.scheduler.HawkeyeExecutors;
import gg.moonrise.hawkeye.core.storage.EventStore;
import gg.moonrise.hawkeye.core.storage.Shard;
import gg.moonrise.hawkeye.core.storage.ShardScope;
import gg.moonrise.hawkeye.core.storage.StorageManager;
import gg.moonrise.hawkeye.core.storage.StorageState;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.entity.query.LookupQuery;
import gg.moonrise.hawkeye.entity.query.ServerScope;
import gg.moonrise.moss.spring.SpringComponent;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Rollback, restore, preview, apply, cancel and undo on this server's worlds.
 *
 * <p>An operation flushes the write queue (so it sees every event captured before the command),
 * counts and reads the matching events of this server's worlds on the home shard in pages on the
 * query executor, plans the result ({@link RollbackPlanner}), changes the world chunk by chunk on
 * each chunk's region ({@link ChunkApplier}) and finally flags the events. Each issuer runs one
 * operation at a time and can undo their last one.
 *
 * <p>Network seam: {@link #run} refuses a query that names other servers with
 * {@link RollbackOutcome.NetworkUnsupported}; that branch is where network jobs get dispatched. A
 * job's target server applies its part with {@link #runLocal}, which never looks at the query's
 * server scope.
 */
@Slf4j
@SpringComponent
@RequiredArgsConstructor
public final class RollbackService {

    /** Events per page read for planning. */
    public static final int PAGE_SIZE = 5_000;

    private static final long PROGRESS_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(2);

    private final @NotNull StorageManager storage;
    private final @NotNull EventStore store;
    private final @NotNull EventWriter writer;
    private final @NotNull HawkeyeExecutors executors;
    private final @NotNull HawkeyeConfigProvider configProvider;
    private final @NotNull RollbackPlanner planner;
    private final @NotNull ChunkApplier applier;
    private final @NotNull RollbackPreviews previews;
    private final Set<String> running = ConcurrentHashMap.newKeySet();
    private final Map<String, LastOperation> lastOperations = new ConcurrentHashMap<>();

    /**
     * Runs a rollback or restore (or shows its preview).
     *
     * @param request the operation
     * @return how it ended; never fails exceptionally
     */
    public @NotNull CompletableFuture<RollbackOutcome> run(@NotNull RollbackRequest request) {
        if (!isLocal(request.query().servers())) {
            return done(new RollbackOutcome.NetworkUnsupported());
        }
        return runLocal(request);
    }

    /**
     * Runs a rollback or restore on this server's own worlds, whatever server scope the query names.
     *
     * @param request the operation
     * @return how it ended; never fails exceptionally
     */
    public @NotNull CompletableFuture<RollbackOutcome> runLocal(@NotNull RollbackRequest request) {
        Optional<StorageState.Ready> ready = storage.readyNow();
        if (ready.isEmpty()) {
            return done(new RollbackOutcome.NotReady());
        }
        if (request.preview() && request.issuer().player().isEmpty()) {
            return done(new RollbackOutcome.PreviewNeedsPlayer());
        }
        String key = request.issuer().key();
        if (!running.add(key)) {
            return done(new RollbackOutcome.AlreadyRunning());
        }
        long start = System.nanoTime();
        Shard home = ready.get().home();
        return writer.flush()
                .thenCompose(ignored -> onQuery(() -> plan(home, request)))
                .thenCompose(planning -> switch (planning) {
                    case Planning.Refused refused -> done(refused.outcome());
                    case Planning.Planned planned -> request.preview() ? done(preview(request, planned.plan()))
                            : apply(request, planned.plan(), start);
                })
                .exceptionally(error -> failed(request, error))
                .whenComplete((outcome, error) -> running.remove(key));
    }

    /**
     * Applies the issuer's preview to the world.
     *
     * @param issuer   the player who previewed
     * @param listener hears progress
     * @return how it ended; never fails exceptionally
     */
    public @NotNull CompletableFuture<RollbackOutcome> apply(@NotNull RollbackIssuer issuer,
            @NotNull RollbackListener listener) {
        Optional<Player> player = issuer.player();
        if (player.isEmpty()) {
            return done(new RollbackOutcome.PreviewNeedsPlayer());
        }
        if (storage.readyNow().isEmpty()) {
            return done(new RollbackOutcome.NotReady());
        }
        if (!running.add(issuer.key())) {
            return done(new RollbackOutcome.AlreadyRunning());
        }
        Optional<RollbackPreviews.Preview> preview = previews.take(player.get().getUniqueId());
        if (preview.isEmpty()) {
            running.remove(issuer.key());
            return done(new RollbackOutcome.NoPreview());
        }
        RollbackRequest previewed = preview.get().request();
        RollbackRequest request = new RollbackRequest(previewed.mode(), previewed.query(), issuer, false, listener);
        return apply(request, preview.get().plan(), System.nanoTime())
                .exceptionally(error -> failed(request, error))
                .whenComplete((outcome, error) -> running.remove(issuer.key()));
    }

    /**
     * Drops the issuer's preview and sends them the real blocks again.
     *
     * @param issuer the player who previewed
     * @return {@link RollbackOutcome.PreviewCancelled}, or why not
     */
    public @NotNull RollbackOutcome cancel(@NotNull RollbackIssuer issuer) {
        Optional<UUID> player = issuer.player().map(Player::getUniqueId);
        if (player.isEmpty()) {
            return new RollbackOutcome.PreviewNeedsPlayer();
        }
        return previews.cancel(player.get()) ? new RollbackOutcome.PreviewCancelled()
                : new RollbackOutcome.NoPreview();
    }

    /**
     * Reverses the issuer's last applied rollback or restore: the opposite mode over the same query.
     *
     * @param issuer   who undoes
     * @param listener hears progress
     * @return how it ended; never fails exceptionally
     */
    public @NotNull CompletableFuture<RollbackOutcome> undo(@NotNull RollbackIssuer issuer,
            @NotNull RollbackListener listener) {
        LastOperation last = lastOperations.get(issuer.key());
        if (last == null) {
            return done(new RollbackOutcome.NothingToUndo());
        }
        return run(new RollbackRequest(last.mode().opposite(), last.query(), issuer, false, listener));
    }

    /**
     * Whether a query stays on this server: no {@code s:}, or {@code s:} naming only this server.
     *
     * @param servers the query's server scope
     * @return {@code true} when only this server's worlds are meant
     */
    public boolean isLocal(@NotNull ServerScope servers) {
        return switch (servers) {
            case ServerScope.Local local -> true;
            case ServerScope.Named named -> named.names().equals(Set.of(configProvider.get().server().name()));
            case ServerScope.Network network -> false;
        };
    }

    private @NotNull Planning plan(@NotNull Shard home, @NotNull RollbackRequest request) {
        RollbackSettings settings = configProvider.get().rollback();
        RollbackMode mode = request.mode();
        PlanOptions options = PlanOptions.of(settings, request.issuer().origin());
        Optional<LookupQuery> query = planner.query(request.query(), mode, options);
        if (query.isEmpty()) {
            return new Planning.Refused(new RollbackOutcome.NothingToDo(mode));
        }
        ShardScope scope = new ShardScope.Scopes(Set.of(storage.worldScope()));
        try {
            long count = store.count(home, query.get(), scope);
            if (count == 0) {
                return new Planning.Refused(new RollbackOutcome.NothingToDo(mode));
            }
            if (settings.maxEvents() > 0 && count > settings.maxEvents()) {
                return new Planning.Refused(new RollbackOutcome.TooLarge(count, settings.maxEvents()));
            }
            notify(() -> request.listener().started(mode, count));
            PlanBuilder builder = planner.builder(mode, options);
            long cursor = 0;
            while (true) {
                List<StoredEvent> page = store.find(home, query.get(), scope, cursor, PAGE_SIZE, false);
                page.forEach(builder::add);
                if (page.size() < PAGE_SIZE) {
                    break;
                }
                cursor = page.getLast().id();
            }
            RollbackPlan plan = builder.build();
            return plan.isEmpty() ? new Planning.Refused(new RollbackOutcome.NothingToDo(mode))
                    : new Planning.Planned(plan);
        } catch (SQLException e) {
            throw new CompletionException(e);
        }
    }

    private @NotNull RollbackOutcome preview(@NotNull RollbackRequest request, @NotNull RollbackPlan plan) {
        int minutes = configProvider.get().rollback().previewMinutes();
        Player player = request.issuer().player().orElseThrow();
        RollbackPreviews.Preview preview = previews.show(request, plan, player, minutes);
        return new RollbackOutcome.Previewed(request.mode(), preview.plan().blocks(), minutes);
    }

    private @NotNull CompletableFuture<RollbackOutcome> apply(@NotNull RollbackRequest request,
            @NotNull RollbackPlan plan, long startNanos) {
        RollbackSettings settings = configProvider.get().rollback();
        return applier.apply(plan, settings.blocksPerTick(), settings.concurrentChunks(), throttled(request))
                .thenCompose(result -> onQuery(() -> flag(request, plan, result, startNanos)));
    }

    private @NotNull RollbackOutcome flag(@NotNull RollbackRequest request, @NotNull RollbackPlan plan,
            @NotNull ChunkApplier.Result result, long startNanos) {
        int flagged;
        try {
            flagged = store.setRolledBack(storage.home(), plan.eventIds(), plan.mode().flagsRolledBack());
        } catch (SQLException e) {
            throw new CompletionException(e);
        }
        lastOperations.put(request.issuer().key(), new LastOperation(request.mode(), request.query()));
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        log.info("{} by {}: {} event(s), {} block(s), {} item change(s), {} entit(ies), {} skipped, {} ms",
                request.mode(), request.issuer().name(), flagged, result.blocks(), result.items(), result.entities(),
                result.skipped(), millis);
        return new RollbackOutcome.Applied(request.mode(), flagged, result.blocks(), result.items(), result.entities(),
                millis);
    }

    private @NotNull ChunkApplier.Progress throttled(@NotNull RollbackRequest request) {
        AtomicLong last = new AtomicLong(System.nanoTime());
        return (done, total) -> {
            long now = System.nanoTime();
            long previous = last.get();
            if (done < total && now - previous >= PROGRESS_INTERVAL_NANOS && last.compareAndSet(previous, now)) {
                notify(() -> request.listener().progress(request.mode(), done, total));
            }
        };
    }

    private @NotNull RollbackOutcome failed(@NotNull RollbackRequest request, @NotNull Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        log.warn("{} by {} failed", request.mode(), request.issuer().name(), cause);
        return new RollbackOutcome.Failed(Objects.requireNonNullElse(cause.getMessage(),
                cause.getClass().getSimpleName()));
    }

    /**
     * Runs work on the query executor. A full executor fails the future instead of throwing into the
     * thread that completed the previous stage (the writer or a region thread).
     */
    private <T> @NotNull CompletableFuture<T> onQuery(@NotNull Supplier<T> work) {
        try {
            return CompletableFuture.supplyAsync(work, executors.query());
        } catch (RejectedExecutionException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private static void notify(@NotNull Runnable call) {
        try {
            call.run();
        } catch (RuntimeException e) {
            log.warn("A rollback listener failed", e);
        }
    }

    private static @NotNull CompletableFuture<RollbackOutcome> done(@NotNull RollbackOutcome outcome) {
        return CompletableFuture.completedFuture(outcome);
    }

    /** The planning step's result. */
    private sealed interface Planning {

        /**
         * Ready to apply or preview.
         *
         * @param plan the plan
         */
        record Planned(@NotNull RollbackPlan plan) implements Planning {
        }

        /**
         * Nothing to apply.
         *
         * @param outcome why
         */
        record Refused(@NotNull RollbackOutcome outcome) implements Planning {
        }
    }

    /**
     * An issuer's last applied operation, for undo.
     *
     * @param mode  the mode
     * @param query the issuer's query
     */
    private record LastOperation(@NotNull RollbackMode mode, @NotNull LookupQuery query) {
    }
}
