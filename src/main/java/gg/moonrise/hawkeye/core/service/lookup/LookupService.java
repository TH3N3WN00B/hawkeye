package gg.moonrise.hawkeye.core.service.lookup;

import gg.moonrise.hawkeye.config.HawkeyeConfigProvider;
import gg.moonrise.hawkeye.config.file.LookupSettings;
import gg.moonrise.hawkeye.core.queue.EventWriter;
import gg.moonrise.hawkeye.core.scheduler.HawkeyeExecutors;
import gg.moonrise.hawkeye.core.service.query.QueryTargets;
import gg.moonrise.hawkeye.core.storage.Archive;
import gg.moonrise.hawkeye.core.storage.EventStore;
import gg.moonrise.hawkeye.core.storage.StorageManager;
import gg.moonrise.hawkeye.entity.query.LookupQuery;
import gg.moonrise.moss.spring.SpringComponent;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

/**
 * Runs lookups against storage. Every lookup first flushes the write queue (bounded by
 * {@code lookup.flush-wait-millis}) so it sees what just happened, then reads every shard its server
 * scope covers in parallel on the query pool and merges the results ({@link LookupMerge}). A shard that
 * fails becomes a warning; a lookup that runs past {@code lookup.timeout-seconds} is cancelled and
 * reported as {@link LookupOutcome.TimedOut}. Nothing here blocks the calling thread.
 */
@Slf4j
@SpringComponent
@RequiredArgsConstructor
public final class LookupService {

    /** The reason of a lookup refused because the query pool and its queue are full. */
    public static final String BUSY = "busy";

    private final @NotNull StorageManager storage;
    private final @NotNull EventStore store;
    private final @NotNull QueryTargets queryTargets;
    private final @NotNull EventWriter writer;
    private final @NotNull HawkeyeExecutors executors;
    private final @NotNull HawkeyeConfigProvider configProvider;

    /**
     * Reads one page, newest first, of the events older than a cursor.
     *
     * @param query    the query
     * @param cursor   the exclusive upper id bound (the previous page's last id), 0 for the newest events
     * @param pageSize the page size
     * @return a {@link LookupOutcome.Page} or a {@link LookupOutcome.Problem}
     */
    public @NotNull CompletableFuture<LookupOutcome> page(@NotNull LookupQuery query, long cursor, int pageSize) {
        int limit = pageSize + 1;
        return run(target -> store.find(target.shard(), query, target.scope(), cursor, limit, true),
                query, results -> LookupMerge.page(results, pageSize));
    }

    /**
     * Counts the matching events exactly, however many archive segments that reads.
     *
     * @param query the query
     * @return an exact {@link LookupOutcome.Count} or a {@link LookupOutcome.Problem}
     */
    public @NotNull CompletableFuture<LookupOutcome> count(@NotNull LookupQuery query) {
        return count(query, Archive.ALL_SEGMENTS);
    }

    /**
     * Counts the matching events, reading the bodies of at most {@code maxSegments} archive segments per
     * shard, newest first; the hot tier is always counted exactly.
     *
     * @param query       the query
     * @param maxSegments the segment budget per shard; {@link Archive#ALL_SEGMENTS} for an exact count
     * @return a {@link LookupOutcome.Count} (a lower bound when a budget ran out) or a
     *         {@link LookupOutcome.Problem}
     */
    public @NotNull CompletableFuture<LookupOutcome> count(@NotNull LookupQuery query, int maxSegments) {
        return run(target -> store.count(target.shard(), query, target.scope(), maxSegments), query,
                LookupMerge::count);
    }

    private <T> @NotNull CompletableFuture<LookupOutcome> run(@NotNull ShardRead<T> read, @NotNull LookupQuery query,
            @NotNull Function<List<ShardResult<T>>, LookupOutcome> merge) {
        if (storage.readyNow().isEmpty()) {
            return CompletableFuture.completedFuture(new LookupOutcome.NotReady());
        }
        LookupSettings settings = configProvider.get().lookup();
        Run<T> run = new Run<>(read, query, merge);
        run.result.completeOnTimeout(new LookupOutcome.TimedOut(), settings.timeoutSeconds(), TimeUnit.SECONDS);
        run.result.whenComplete((outcome, error) -> {
            if (outcome instanceof LookupOutcome.TimedOut) {
                log.warn("A lookup ran past {} s and was cancelled", settings.timeoutSeconds());
                run.cancel();
            }
        });
        writer.flush().copy()
                .completeOnTimeout(null, settings.flushWaitMillis(), TimeUnit.MILLISECONDS)
                .whenComplete((flushed, error) -> run.submit(run::start));
        return run.result;
    }

    private static @NotNull String reason(@NotNull Throwable error) {
        return Objects.requireNonNullElse(error.getMessage(), error.getClass().getSimpleName());
    }

    /** Reads one target shard; blocking, on a query thread. */
    @FunctionalInterface
    private interface ShardRead<T> {

        @NotNull T read(@NotNull QueryTargets.Target target) throws SQLException;
    }

    /** One lookup in flight: its result and every task it submitted, so a timeout can cancel them. */
    private final class Run<T> {

        private final CompletableFuture<LookupOutcome> result = new CompletableFuture<>();
        private final List<Future<?>> tasks = new CopyOnWriteArrayList<>();
        private final ShardRead<T> read;
        private final LookupQuery query;
        private final Function<List<ShardResult<T>>, LookupOutcome> merge;

        private Run(@NotNull ShardRead<T> read, @NotNull LookupQuery query,
                @NotNull Function<List<ShardResult<T>>, LookupOutcome> merge) {
            this.read = read;
            this.query = query;
            this.merge = merge;
        }

        /** Resolves the targets; reads a single target here, more than one in parallel. */
        private void start() {
            List<QueryTargets.Target> targets;
            try {
                targets = queryTargets.targets(query.servers());
            } catch (SQLException | RuntimeException e) {
                log.warn("A lookup could not resolve its servers: {}", reason(e));
                result.complete(new LookupOutcome.Failed(reason(e)));
                return;
            }
            if (targets.size() <= 1) {
                List<ShardResult<T>> results = targets.stream().map(this::readShard).toList();
                complete(results);
                return;
            }
            List<CompletableFuture<ShardResult<T>>> reads = new ArrayList<>(targets.size());
            for (QueryTargets.Target target : targets) {
                CompletableFuture<ShardResult<T>> shard = new CompletableFuture<>();
                reads.add(shard);
                if (!submit(() -> shard.complete(readShard(target)))) {
                    return;
                }
            }
            CompletableFuture.allOf(reads.toArray(new CompletableFuture<?>[0]))
                    .thenRun(() -> complete(reads.stream().map(CompletableFuture::join).toList()));
        }

        private @NotNull ShardResult<T> readShard(@NotNull QueryTargets.Target target) {
            String shard = target.shard().name();
            try {
                return new ShardResult.Read<>(shard, read.read(target));
            } catch (SQLException | RuntimeException e) {
                if (!result.isDone()) {
                    log.warn("A lookup could not read shard {}: {}", shard, reason(e));
                }
                return new ShardResult.Unreadable<>(shard, reason(e));
            }
        }

        private void complete(@NotNull List<ShardResult<T>> results) {
            try {
                result.complete(merge.apply(results));
            } catch (RuntimeException e) {
                log.error("A lookup could not merge its results", e);
                result.complete(new LookupOutcome.Failed(reason(e)));
            }
        }

        /**
         * Submits a task to the query pool, failing the lookup when the pool refuses it.
         *
         * @return whether the task was accepted
         */
        private boolean submit(@NotNull Runnable task) {
            if (result.isDone()) {
                return false;
            }
            try {
                tasks.add(executors.query().submit(task));
                return true;
            } catch (RejectedExecutionException e) {
                log.warn("A lookup was refused: the query pool is full");
                result.complete(new LookupOutcome.Failed(BUSY));
                cancel();
                return false;
            }
        }

        private void cancel() {
            tasks.forEach(task -> task.cancel(true));
        }
    }
}
