package gg.moonrise.hawkeye.core.service.purge;

import gg.moonrise.hawkeye.config.HawkeyeConfigProvider;
import gg.moonrise.hawkeye.config.file.PurgeSettings;
import gg.moonrise.hawkeye.core.repository.network.LeaseRepository;
import gg.moonrise.hawkeye.core.scheduler.HawkeyeExecutors;
import gg.moonrise.hawkeye.core.storage.shard.StorageManager;
import gg.moonrise.hawkeye.core.storage.shard.StorageState;
import gg.moonrise.moss.spring.Disableable;
import gg.moonrise.moss.spring.Enableable;
import gg.moonrise.moss.spring.SpringComponent;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

/**
 * Deletes events older than {@code purge.retention-days} every {@code purge.interval-hours}, on the
 * maintenance thread. Every server sharing a database runs this, but only the holder of the
 * {@value #LEASE} lease purges: it renews the lease on each run (for two intervals), and another
 * server takes over when the holder stops renewing. The lease is given up on shutdown. Changing the
 * settings with {@code /hawkeye reload} applies from the next run.
 */
@Slf4j
@SpringComponent
@RequiredArgsConstructor
public final class AutoPurge implements Enableable, Disableable {

    /** The lease name in the {@code lease} table. */
    public static final String LEASE = "auto-purge";

    /** The first run waits this long after enable, so start-up and the database connection settle first. */
    public static final Duration FIRST_RUN = Duration.ofMinutes(5);

    private final @NotNull HawkeyeConfigProvider configProvider;
    private final @NotNull StorageManager storage;
    private final @NotNull HawkeyeExecutors executors;
    private final @NotNull PurgeService purgeService;
    private final @NotNull LeaseRepository leases;
    private final @NotNull Clock clock;
    private volatile ScheduledFuture<?> next;
    private volatile boolean held;
    private volatile boolean stopped;

    @Override
    public void onEnable() {
        schedule(FIRST_RUN);
    }

    @Override
    public void onDisable() {
        stopped = true;
        ScheduledFuture<?> scheduled = next;
        if (scheduled != null) {
            scheduled.cancel(false);
        }
        if (held) {
            storage.readyNow().ifPresent(ready -> {
                try {
                    leases.release(ready.home().database(), LEASE, holder());
                } catch (SQLException | RuntimeException e) {
                    log.debug("Could not release the auto-purge lease; it expires on its own", e);
                }
            });
            held = false;
        }
    }

    /**
     * Runs once now: takes or renews the lease (a database call on the calling thread), then purges
     * when this server holds it.
     *
     * @return what the run did; never fails exceptionally
     */
    public @NotNull CompletableFuture<AutoPurgeResult> runOnce() {
        PurgeSettings settings = configProvider.get().purge();
        if (settings.retentionDays() <= 0) {
            return CompletableFuture.completedFuture(new AutoPurgeResult.Disabled());
        }
        Optional<StorageState.Ready> ready = storage.readyNow();
        if (ready.isEmpty()) {
            return CompletableFuture.completedFuture(new AutoPurgeResult.NotReady());
        }
        long ttl = Duration.ofHours(settings.intervalHours()).multipliedBy(2).plus(FIRST_RUN).toMillis();
        try {
            held = leases.tryAcquire(ready.get().home().database(), LEASE, holder(), clock.millis(), ttl);
        } catch (SQLException | RuntimeException e) {
            return CompletableFuture.completedFuture(new AutoPurgeResult.Failed(
                    Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName())));
        }
        if (!held) {
            return CompletableFuture.completedFuture(new AutoPurgeResult.NotLeaseHolder());
        }
        Duration age = Duration.ofDays(settings.retentionDays());
        return purgeService.purge(new PurgeRequest(age, Set.of(), false))
                .thenApply(AutoPurgeResult.Ran::new);
    }

    /**
     * Whether this server held the lease at its last run.
     *
     * @return {@code true} when it purges for the shard
     */
    public boolean holdsLease() {
        return held;
    }

    private void tick() {
        CompletableFuture<AutoPurgeResult> run;
        try {
            run = runOnce();
        } catch (RuntimeException e) {
            log.warn("The automatic purge failed", e);
            run = CompletableFuture.completedFuture(new AutoPurgeResult.Failed(e.getClass().getSimpleName()));
        }
        run.whenComplete((result, error) -> {
            if (result != null) {
                report(result);
            }
            schedule(Duration.ofHours(configProvider.get().purge().intervalHours()));
        });
    }

    private void report(@NotNull AutoPurgeResult result) {
        switch (result) {
            case AutoPurgeResult.Ran ran -> {
                switch (ran.outcome()) {
                    case PurgeOutcome.Purged purged -> log.info("Automatic purge deleted {} event(s) in {} ms",
                            purged.events(), purged.millis());
                    case PurgeOutcome.Failed failed -> log.warn("Automatic purge failed: {}", failed.reason());
                    case PurgeOutcome.AlreadyRunning running ->
                            log.info("Automatic purge skipped: a purge is already running");
                    case PurgeOutcome.NotReady notReady -> log.debug("Automatic purge skipped: storage not ready");
                    case PurgeOutcome.TooRecent tooRecent -> log.debug("Automatic purge skipped: too recent");
                }
            }
            case AutoPurgeResult.Failed failed -> log.warn("Automatic purge could not take its lease: {}",
                    failed.reason());
            case AutoPurgeResult.NotLeaseHolder notHolder ->
                    log.debug("Automatic purge left to the server holding the lease");
            case AutoPurgeResult.NotReady notReady -> log.debug("Automatic purge skipped: storage not ready");
            case AutoPurgeResult.Disabled disabled -> log.debug("Automatic purge is off");
        }
    }

    private void schedule(@NotNull Duration delay) {
        if (stopped) {
            return;
        }
        try {
            next = executors.maintenance().schedule(this::tick, delay.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            log.debug("Not scheduling the automatic purge: the plugin is shutting down");
        }
    }

    private @NotNull String holder() {
        return configProvider.get().server().name();
    }
}
