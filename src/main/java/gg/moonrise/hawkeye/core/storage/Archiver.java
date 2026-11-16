package gg.moonrise.hawkeye.core.storage;

import gg.moonrise.hawkeye.config.HawkeyeConfigProvider;
import gg.moonrise.hawkeye.config.file.ArchiveSettings;
import gg.moonrise.hawkeye.core.repository.LeaseRepository;
import gg.moonrise.hawkeye.core.scheduler.HawkeyeExecutors;
import gg.moonrise.moss.spring.Disableable;
import gg.moonrise.moss.spring.Enableable;
import gg.moonrise.moss.spring.SpringComponent;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

/**
 * Runs the archive ({@link Archive#compact}) every {@code archive.interval-minutes} on the server that
 * holds the shard's {@code archive} lease, so a whole network can share one configuration. Each pass
 * is one transaction of {@code archive.batch-events} events on the query pool; passes follow each
 * other until nothing old is left, so the maintenance thread (heartbeats, network jobs) is never held.
 */
@Slf4j
@SpringComponent
@RequiredArgsConstructor
public final class Archiver implements Enableable, Disableable {

    /** The lease name in the {@code lease} table. */
    public static final String LEASE = "archive";

    /** The first run waits this long after enable. */
    public static final Duration FIRST_RUN = Duration.ofMinutes(2);

    private final @NotNull HawkeyeConfigProvider configProvider;
    private final @NotNull StorageManager storage;
    private final @NotNull Archive archive;
    private final @NotNull LeaseRepository leases;
    private final @NotNull HawkeyeExecutors executors;
    private final @NotNull Clock clock;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong archived = new AtomicLong();
    private volatile boolean held;
    private volatile boolean stopped;

    @Override
    public void onEnable() {
        long interval = TimeUnit.MINUTES.toSeconds(configProvider.get().archive().intervalMinutes());
        executors.maintenance().scheduleWithFixedDelay(this::tick, FIRST_RUN.toSeconds(), interval, TimeUnit.SECONDS);
    }

    @Override
    public void onDisable() {
        stopped = true;
        if (held) {
            storage.readyNow().ifPresent(ready -> {
                try {
                    leases.release(ready.home().database(), LEASE, holder());
                } catch (SQLException | RuntimeException e) {
                    log.debug("Could not release the archive lease; it expires on its own", e);
                }
            });
            held = false;
        }
    }

    /**
     * Archives everything old enough now, when this server holds the lease.
     *
     * @return the number of events archived (0 when disabled, not ready, not the holder or busy)
     */
    public @NotNull CompletableFuture<Long> runOnce() {
        ArchiveSettings settings = configProvider.get().archive();
        Optional<StorageState.Ready> ready = storage.readyNow();
        if (!settings.enabled() || ready.isEmpty() || stopped || !running.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(0L);
        }
        Shard home = ready.get().home();
        long ttl = Duration.ofMinutes(settings.intervalMinutes()).multipliedBy(3).toMillis();
        try {
            held = leases.tryAcquire(home.database(), LEASE, holder(), clock.millis(), ttl);
        } catch (SQLException | RuntimeException e) {
            running.set(false);
            log.debug("Could not take the archive lease", e);
            return CompletableFuture.completedFuture(0L);
        }
        if (!held) {
            running.set(false);
            return CompletableFuture.completedFuture(0L);
        }
        long before = clock.millis() - Duration.ofHours(settings.afterHours()).toMillis();
        return passes(home, before, settings.batchEvents(), 0).whenComplete((total, error) -> {
            running.set(false);
            if (error != null) {
                log.warn("Archiving failed; it is retried on the next run", error);
            } else if (total > 0) {
                archived.addAndGet(total);
                log.info("Archived {} event(s) older than {} hour(s)", total, settings.afterHours());
            }
        });
    }

    /**
     * Events this server archived since start.
     *
     * @return the count
     */
    public long archivedSinceStart() {
        return archived.get();
    }

    private @NotNull CompletableFuture<Long> passes(@NotNull Shard home, long before, int batch, long total) {
        if (stopped) {
            return CompletableFuture.completedFuture(total);
        }
        CompletableFuture<Integer> pass;
        try {
            pass = CompletableFuture.supplyAsync(() -> compact(home, before, batch), executors.query());
        } catch (RejectedExecutionException e) {
            return CompletableFuture.completedFuture(total);
        }
        return pass.thenCompose(moved -> moved < batch ? CompletableFuture.completedFuture(total + moved)
                : passes(home, before, batch, total + moved));
    }

    private int compact(@NotNull Shard home, long before, int batch) {
        try {
            return archive.compact(home, before, batch);
        } catch (SQLException e) {
            throw new IllegalStateException("archive pass failed: " + e.getMessage(), e);
        }
    }

    private void tick() {
        try {
            runOnce();
        } catch (RuntimeException e) {
            log.warn("Archiving failed", e);
        }
    }

    private @NotNull String holder() {
        return configProvider.get().server().name();
    }
}
