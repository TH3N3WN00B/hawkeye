package gg.moonrise.hawkeye.core.service.purge;

import gg.moonrise.hawkeye.config.HawkeyeConfigProvider;
import gg.moonrise.hawkeye.config.file.PurgeSettings;
import gg.moonrise.hawkeye.core.scheduler.HawkeyeExecutors;
import gg.moonrise.hawkeye.core.storage.EventStore;
import gg.moonrise.hawkeye.core.storage.Shard;
import gg.moonrise.hawkeye.core.storage.StorageManager;
import gg.moonrise.hawkeye.core.storage.StorageState;
import gg.moonrise.hawkeye.entity.server.WorldKey;
import gg.moonrise.moss.spring.SpringComponent;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

/**
 * Deletes old events from the home shard in batches of {@code purge.batch-size}, one batch per query
 * executor task, so a long purge never holds a query thread or long table locks. One purge runs at a
 * time, by hand or automatic ({@link AutoPurge}).
 */
@Slf4j
@SpringComponent
@RequiredArgsConstructor
public final class PurgeService {

    private final @NotNull StorageManager storage;
    private final @NotNull EventStore store;
    private final @NotNull HawkeyeExecutors executors;
    private final @NotNull HawkeyeConfigProvider configProvider;
    private final @NotNull Clock clock;
    private final AtomicBoolean running = new AtomicBoolean();

    /**
     * Runs a purge.
     *
     * @param request what to delete
     * @return how it ended; never fails exceptionally
     */
    public @NotNull CompletableFuture<PurgeOutcome> purge(@NotNull PurgeRequest request) {
        return purge(request, () -> {
        });
    }

    /**
     * Runs a purge.
     *
     * @param request what to delete
     * @param started runs on the calling thread once the purge is accepted, before anything is deleted
     * @return how it ended; never fails exceptionally
     */
    public @NotNull CompletableFuture<PurgeOutcome> purge(@NotNull PurgeRequest request, @NotNull Runnable started) {
        PurgeSettings settings = configProvider.get().purge();
        if (request.limited() && request.olderThan().compareTo(Duration.ofDays(settings.playerMinimumDays())) < 0) {
            return done(new PurgeOutcome.TooRecent(settings.playerMinimumDays()));
        }
        Optional<StorageState.Ready> ready = storage.readyNow();
        if (ready.isEmpty()) {
            return done(new PurgeOutcome.NotReady());
        }
        if (!running.compareAndSet(false, true)) {
            return done(new PurgeOutcome.AlreadyRunning());
        }
        try {
            started.run();
        } catch (RuntimeException e) {
            log.warn("A purge start listener failed", e);
        }
        Shard home = ready.get().home();
        long beforeMillis = clock.millis() - request.olderThan().toMillis();
        int batchSize = settings.batchSize();
        long start = System.nanoTime();
        return onQuery(() -> worldIds(home, request.worlds()))
                .thenCompose(worlds -> worlds.map(ids -> batches(home, beforeMillis, ids, batchSize, 0))
                        .orElseGet(() -> CompletableFuture.completedFuture(0L)))
                .<PurgeOutcome>thenApply(deleted -> new PurgeOutcome.Purged(deleted,
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)))
                .exceptionally(PurgeService::failed)
                .whenComplete((outcome, error) -> running.set(false));
    }

    /**
     * Whether a purge is running.
     *
     * @return {@code true} while one runs
     */
    public boolean running() {
        return running.get();
    }

    /** World ids to purge: empty for all; empty optional when none of the named worlds has events. */
    private @NotNull Optional<Set<Integer>> worldIds(@NotNull Shard home, @NotNull Set<String> names) {
        if (names.isEmpty()) {
            return Optional.of(Set.of());
        }
        try {
            Map<Integer, WorldKey> worlds = store.worlds(home, Set.of(storage.worldScope()));
            Set<Integer> ids = worlds.entrySet().stream()
                    .filter(entry -> names.contains(entry.getValue().name()))
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toSet());
            return ids.isEmpty() ? Optional.empty() : Optional.of(ids);
        } catch (SQLException e) {
            throw new CompletionException(e);
        }
    }

    private @NotNull CompletableFuture<Long> batches(@NotNull Shard home, long beforeMillis, @NotNull Set<Integer> worlds,
            int batchSize, long total) {
        return onQuery(() -> deleteBatch(home, beforeMillis, worlds, batchSize))
                // A batch deletes hot rows first, then archive segments; only an empty batch means done.
                .thenCompose(deleted -> deleted == 0 ? CompletableFuture.completedFuture(total)
                        : batches(home, beforeMillis, worlds, batchSize, total + deleted));
    }

    private int deleteBatch(@NotNull Shard home, long beforeMillis, @NotNull Set<Integer> worlds, int batchSize) {
        try {
            return store.purgeBatch(home, beforeMillis, worlds, batchSize);
        } catch (SQLException e) {
            throw new CompletionException(e);
        }
    }

    /** Runs work on the query executor; a full executor fails the future instead of throwing. */
    private <T> @NotNull CompletableFuture<T> onQuery(@NotNull Supplier<T> work) {
        try {
            return CompletableFuture.supplyAsync(work, executors.query());
        } catch (RejectedExecutionException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private static @NotNull PurgeOutcome failed(@NotNull Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        log.warn("Purge failed", cause);
        return new PurgeOutcome.Failed(Objects.requireNonNullElse(cause.getMessage(), cause.getClass().getSimpleName()));
    }

    private static @NotNull CompletableFuture<PurgeOutcome> done(@NotNull PurgeOutcome outcome) {
        return CompletableFuture.completedFuture(outcome);
    }
}
