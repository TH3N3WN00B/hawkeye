package gg.moonrise.hawkeye.core.service.network;

import gg.moonrise.hawkeye.config.HawkeyeConfigProvider;
import gg.moonrise.hawkeye.core.bus.SyncBus;
import gg.moonrise.hawkeye.core.repository.network.JobRepository;
import gg.moonrise.hawkeye.core.scheduler.HawkeyeExecutors;
import gg.moonrise.hawkeye.core.service.query.QueryTargets;
import gg.moonrise.hawkeye.core.storage.shard.Shard;
import gg.moonrise.hawkeye.core.storage.shard.StorageManager;
import gg.moonrise.hawkeye.core.storage.shard.StorageState;
import gg.moonrise.hawkeye.entity.job.JobKind;
import gg.moonrise.hawkeye.entity.job.JobResult;
import gg.moonrise.hawkeye.entity.job.JobStatus;
import gg.moonrise.hawkeye.entity.job.NetworkJob;
import gg.moonrise.hawkeye.entity.query.LookupQuery;
import gg.moonrise.hawkeye.entity.query.ServerScope;
import gg.moonrise.hawkeye.entity.server.ServerNode;
import gg.moonrise.hawkeye.util.codec.CodecException;
import gg.moonrise.hawkeye.util.codec.QueryCodec;
import gg.moonrise.moss.spring.Enableable;
import gg.moonrise.moss.spring.SpringComponent;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Rollbacks and restores across servers. A server can only change its own worlds, so the issuer
 * writes one {@code job} row per target server into the target's shard and publishes a wake-up on the
 * bus; each server applies the jobs addressed to it (claimed atomically, so a job runs once), writes
 * the result and publishes "done"; the issuer reads the result and tells the sender. Every server
 * also polls the database every {@code network.poll-interval-seconds}, so the bus is an accelerator,
 * never a requirement: with {@code bus: NONE}, or a lost message, jobs still run after one poll.
 */
@Slf4j
@SpringComponent
@RequiredArgsConstructor
public final class NetworkJobService implements Enableable {

    /** A job still running after this long belonged to a server that stopped mid-job. */
    public static final long ABANDON_MILLIS = TimeUnit.HOURS.toMillis(1);

    /** Finished jobs are deleted after this long. */
    public static final long PRUNE_MILLIS = TimeUnit.DAYS.toMillis(7);

    /** An issuer stops waiting for a job's result after this long. */
    public static final long WATCH_MILLIS = TimeUnit.HOURS.toMillis(6);

    private final @NotNull StorageManager storage;
    private final @NotNull QueryTargets targets;
    private final @NotNull JobRepository jobRepository;
    private final @NotNull SyncBus bus;
    private final @NotNull HawkeyeExecutors executors;
    private final @NotNull HawkeyeConfigProvider configProvider;
    private final @NotNull Clock clock;
    private final @NotNull ObjectProvider<JobRunner> runners;

    private final Map<WatchKey, Watched> watched = new ConcurrentHashMap<>();
    private final Set<Long> running = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean polling = new AtomicBoolean();

    @Override
    public void onEnable() {
        String me = serverName();
        bus.subscribe("jobs:" + me, message -> pollSoon());
        bus.subscribe("done:" + me, message -> pollSoon());
        long interval = configProvider.get().network().pollIntervalSeconds();
        executors.maintenance().scheduleWithFixedDelay(this::poll, interval, interval, TimeUnit.SECONDS);
        storage.ready().thenRunAsync(this::recover, executors.maintenance());
    }

    /**
     * Writes a job for every server of a scope and watches for the results.
     *
     * @param kind    rollback or restore
     * @param query   the query; its server scope selects the targets, its other filters travel with the job
     * @param issuer  who asked (a player name or {@code #console})
     * @param watcher told about every target's result
     * @return what was dispatched
     */
    public @NotNull CompletableFuture<DispatchOutcome> dispatch(@NotNull JobKind kind, @NotNull LookupQuery query,
            @NotNull String issuer, @NotNull JobWatcher watcher) {
        try {
            return CompletableFuture.supplyAsync(() -> dispatchNow(kind, query, issuer, watcher), executors.query());
        } catch (RejectedExecutionException e) {
            return CompletableFuture.completedFuture(new DispatchOutcome.Failed("busy"));
        }
    }

    /**
     * Jobs this server is applying right now.
     *
     * @return the count
     */
    public int runningJobs() {
        return running.size();
    }

    /**
     * Jobs this server dispatched and still waits for.
     *
     * @return the count
     */
    public int watchedJobs() {
        return watched.size();
    }

    /** Polls now, on the maintenance thread (the bus calls this on a wake-up). */
    public void pollSoon() {
        try {
            executors.maintenance().execute(this::poll);
        } catch (RejectedExecutionException e) {
            log.debug("Not polling network jobs: shutting down");
        }
    }

    private @NotNull DispatchOutcome dispatchNow(@NotNull JobKind kind, @NotNull LookupQuery query,
            @NotNull String issuer, @NotNull JobWatcher watcher) {
        if (storage.readyNow().isEmpty()) {
            return new DispatchOutcome.NotReady();
        }
        String encoded = QueryCodec.encode(query);
        List<String> dispatched = new ArrayList<>();
        try {
            for (Destination destination : destinations(query.servers())) {
                Shard shard = destination.shard();
                long now = clock.millis();
                long id = shard.database().read(connection -> jobRepository.insert(connection,
                        shard.database().sql(), destination.server(), kind, serverName(), issuer, encoded, now));
                watched.put(new WatchKey(shard.name(), id), new Watched(destination.server(), watcher, now));
                bus.publish("jobs:" + destination.server(), Long.toString(id));
                dispatched.add(destination.server());
            }
        } catch (SQLException | RuntimeException e) {
            return new DispatchOutcome.Failed(reason(e));
        }
        if (dispatched.contains(serverName())) {
            // A job for this server needs no bus round trip.
            pollSoon();
        }
        return dispatched.isEmpty() ? new DispatchOutcome.NoServers() : new DispatchOutcome.Dispatched(dispatched);
    }

    private @NotNull List<Destination> destinations(@NotNull ServerScope scope) throws SQLException {
        List<Destination> destinations = new ArrayList<>();
        for (Shard shard : storage.shards()) {
            for (ServerNode server : targets.servers(shard)) {
                boolean selected = switch (scope) {
                    case ServerScope.Network network -> true;
                    case ServerScope.Named named -> named.names().contains(server.name());
                    case ServerScope.Local local -> server.name().equals(serverName()) && shard.home();
                };
                if (selected) {
                    destinations.add(new Destination(shard, server.name()));
                }
            }
        }
        return destinations;
    }

    private void poll() {
        if (!polling.compareAndSet(false, true)) {
            return;
        }
        try {
            storage.readyNow().ifPresent(this::runMine);
            checkWatched();
        } catch (RuntimeException e) {
            log.warn("Polling network jobs failed", e);
        } finally {
            polling.set(false);
        }
    }

    private void runMine(@NotNull StorageState.Ready ready) {
        Shard home = ready.home();
        List<NetworkJob> pending;
        try {
            pending = home.database().read(connection -> jobRepository.pending(connection, home.database().sql(),
                    ready.node().name()));
        } catch (SQLException e) {
            log.debug("Could not read pending network jobs", e);
            return;
        }
        for (NetworkJob job : pending) {
            if (running.contains(job.id()) || !claim(home, job)) {
                continue;
            }
            running.add(job.id());
            log.info("Applying network {} #{} from {} on {}", job.kind(), job.id(), job.issuer(), job.issuerServer());
            run(job).whenComplete((result, error) -> {
                JobResult outcome = error == null ? result : new JobResult.Failed(reason(error));
                finish(home, job, outcome);
            });
        }
    }

    private boolean claim(@NotNull Shard home, @NotNull NetworkJob job) {
        try {
            return home.database().read(connection -> jobRepository.claim(connection, home.database().sql(), job.id(),
                    clock.millis()));
        } catch (SQLException e) {
            log.debug("Could not claim network job #{}", job.id(), e);
            return false;
        }
    }

    private @NotNull CompletableFuture<JobResult> run(@NotNull NetworkJob job) {
        JobRunner runner = runners.getIfAvailable();
        if (runner == null) {
            return CompletableFuture.completedFuture(new JobResult.Failed("this server cannot apply rollbacks"));
        }
        try {
            return runner.run(job.kind(), QueryCodec.decode(job.query()), job.issuer() + "@" + job.issuerServer());
        } catch (CodecException e) {
            return CompletableFuture.completedFuture(new JobResult.Failed("unreadable job: " + e.getMessage()));
        }
    }

    private void finish(@NotNull Shard home, @NotNull NetworkJob job, @NotNull JobResult result) {
        JobStatus status = result instanceof JobResult.Applied ? JobStatus.DONE : JobStatus.FAILED;
        try {
            home.database().read(connection -> {
                jobRepository.finish(connection, home.database().sql(), job.id(), status, result.encode(),
                        clock.millis());
                return null;
            });
        } catch (SQLException e) {
            log.warn("Could not record the result of network job #{}", job.id(), e);
        } finally {
            running.remove(job.id());
        }
        bus.publish("done:" + job.issuerServer(), Long.toString(job.id()));
        if (job.issuerServer().equals(serverName())) {
            pollSoon();
        }
    }

    private void checkWatched() {
        if (watched.isEmpty()) {
            return;
        }
        long now = clock.millis();
        watched.entrySet().removeIf(entry -> now - entry.getValue().since() > WATCH_MILLIS);
        Map<String, List<Long>> byShard = watched.keySet().stream().collect(Collectors.groupingBy(WatchKey::shard,
                Collectors.mapping(WatchKey::id, Collectors.toList())));
        for (Shard shard : storage.shards()) {
            List<Long> ids = byShard.get(shard.name());
            if (ids == null) {
                continue;
            }
            List<NetworkJob> jobs;
            try {
                jobs = shard.database().read(connection -> jobRepository.byIds(connection, shard.database().sql(),
                        ids));
            } catch (SQLException e) {
                log.debug("Could not read network job results from shard {}", shard.name(), e);
                continue;
            }
            for (NetworkJob job : jobs) {
                if (!job.status().finished()) {
                    continue;
                }
                Watched watch = watched.remove(new WatchKey(shard.name(), job.id()));
                if (watch != null) {
                    JobResult result = Optional.ofNullable(job.result()).flatMap(JobResult::decode)
                            .orElse(new JobResult.Failed("no result recorded"));
                    notify(watch, result);
                }
            }
        }
    }

    private static void notify(@NotNull Watched watch, @NotNull JobResult result) {
        try {
            watch.watcher().finished(watch.server(), result);
        } catch (RuntimeException e) {
            log.warn("A network job watcher failed", e);
        }
    }

    private void recover() {
        storage.readyNow().ifPresent(ready -> {
            Shard home = ready.home();
            long now = clock.millis();
            try {
                int abandoned = home.database().read(connection -> jobRepository.abandon(connection,
                        home.database().sql(), ready.node().name(), now - ABANDON_MILLIS,
                        new JobResult.Failed("the server stopped while applying it").encode(), now));
                int pruned = home.database().read(connection -> jobRepository.prune(connection,
                        home.database().sql(), now - PRUNE_MILLIS));
                if (abandoned > 0 || pruned > 0) {
                    log.info("Network jobs: {} abandoned job(s) marked failed, {} old job(s) deleted", abandoned,
                            pruned);
                }
            } catch (SQLException e) {
                log.debug("Could not clean up network jobs", e);
            }
            poll();
        });
    }

    private @NotNull String serverName() {
        return configProvider.get().server().name();
    }

    private static @NotNull String reason(@NotNull Throwable error) {
        Throwable cause = error.getCause() != null && error instanceof CompletionException
                ? error.getCause() : error;
        return Objects.requireNonNullElse(cause.getMessage(), cause.getClass().getSimpleName());
    }

    private record Destination(@NotNull Shard shard, @NotNull String server) {
    }

    private record WatchKey(@NotNull String shard, long id) {
    }

    private record Watched(@NotNull String server, @NotNull JobWatcher watcher, long since) {
    }
}
