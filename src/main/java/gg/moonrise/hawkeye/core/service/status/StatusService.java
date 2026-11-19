package gg.moonrise.hawkeye.core.service.status;

import gg.moonrise.hawkeye.config.HawkeyeConfigProvider;
import gg.moonrise.hawkeye.core.queue.EventWriter;
import gg.moonrise.hawkeye.core.scheduler.HawkeyeExecutors;
import gg.moonrise.hawkeye.core.service.network.NetworkJobService;
import gg.moonrise.hawkeye.core.storage.archive.Archive;
import gg.moonrise.hawkeye.core.storage.shard.StorageManager;
import gg.moonrise.hawkeye.core.storage.shard.StorageState;
import gg.moonrise.hawkeye.entity.storage.SegmentTotals;
import gg.moonrise.hawkeye.entity.storage.WriterStats;
import gg.moonrise.moss.spring.SpringComponent;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

/** Collects what {@code /hawkeye status} shows. */
@SpringComponent
@RequiredArgsConstructor
public final class StatusService {

    private final @NotNull StorageManager storage;
    private final @NotNull EventWriter writer;
    private final @NotNull HawkeyeConfigProvider configProvider;
    private final @NotNull Archive archive;
    private final @NotNull NetworkJobService networkJobs;
    private final @NotNull HawkeyeExecutors executors;

    /**
     * The status with the archive's size, read from the database on a query thread.
     *
     * @return the full status; the archive figures are -1 when they could not be read
     */
    public @NotNull CompletableFuture<Full> full() {
        Status status = status();
        Jobs jobs = new Jobs(networkJobs.runningJobs(), networkJobs.watchedJobs());
        try {
            return CompletableFuture.supplyAsync(() -> new Full(status, archiveTotals(), jobs), executors.query());
        } catch (RejectedExecutionException e) {
            return CompletableFuture.completedFuture(new Full(status, ArchiveTotals.UNKNOWN, jobs));
        }
    }

    /**
     * The current status.
     *
     * @return a snapshot
     */
    public @NotNull Status status() {
        StorageState state = storage.state();
        Health health = switch (state) {
            case StorageState.Ready ready -> new Health.Ready(ready.node().node());
            case StorageState.Connecting connecting -> new Health.Connecting();
            case StorageState.Failed failed -> new Health.Failed(failed.reason());
            case StorageState.Incompatible incompatible -> new Health.Incompatible(incompatible.version());
        };
        WriterStats stats = writer.stats();
        return new Status(configProvider.get().server().name(), configProvider.get().storage().type().name(), health,
                new Counters(stats.queued(), stats.written(), stats.batches(), stats.dropped(), stats.spooled(),
                        stats.failures(), stats.lastFlushMillis(), stats.lastError()));
    }

    private @NotNull ArchiveTotals archiveTotals() {
        return storage.readyNow().map(ready -> {
            try {
                SegmentTotals totals = archive.totals(ready.home());
                return new ArchiveTotals(totals.events(), totals.segments(), totals.bytes());
            } catch (SQLException | RuntimeException e) {
                return ArchiveTotals.UNKNOWN;
            }
        }).orElse(ArchiveTotals.UNKNOWN);
    }

    /**
     * The status plus what needs a database read.
     *
     * @param status  the in-memory status
     * @param archive the archive's size
     * @param jobs    the network jobs in flight
     */
    public record Full(@NotNull Status status, @NotNull ArchiveTotals archive, @NotNull Jobs jobs) {
    }

    /**
     * The archive's size.
     *
     * @param events   archived events, -1 when unknown
     * @param segments archive segments, -1 when unknown
     * @param bytes    stored segment bytes, -1 when unknown
     */
    public record ArchiveTotals(long events, long segments, long bytes) {

        /** The totals when the database could not be read. */
        public static final ArchiveTotals UNKNOWN = new ArchiveTotals(-1, -1, -1);
    }

    /**
     * Network jobs in flight.
     *
     * @param running jobs this server is applying
     * @param waiting jobs this server dispatched and waits for
     */
    public record Jobs(int running, int waiting) {
    }

    /**
     * What {@code /hawkeye status} shows.
     *
     * @param server   this server's name
     * @param storage  the home storage type
     * @param health   the home database's state
     * @param counters the writer's counters
     */
    public record Status(@NotNull String server, @NotNull String storage, @NotNull Health health,
            @NotNull Counters counters) {
    }

    /** The home database's state. */
    public sealed interface Health {

        /**
         * Connected and registered.
         *
         * @param node this server's node id
         */
        record Ready(int node) implements Health {
        }

        /** Connecting, migrating or registering. */
        record Connecting() implements Health {
        }

        /**
         * The last attempt failed; another follows.
         *
         * @param reason what went wrong
         */
        record Failed(@NotNull String reason) implements Health {
        }

        /**
         * A newer Hawkeye upgraded the database.
         *
         * @param version its schema version
         */
        record Incompatible(int version) implements Health {
        }
    }

    /**
     * The writer's counters since start.
     *
     * @param queued          events waiting
     * @param written         events written
     * @param batches         batches written
     * @param dropped         events dropped on a full queue
     * @param spooled         events spooled to disk
     * @param failures        failed batch attempts
     * @param lastFlushMillis the last batch's duration
     * @param lastError       the last write error, or empty
     */
    public record Counters(int queued, long written, long batches, long dropped, long spooled, long failures,
            long lastFlushMillis, @NotNull String lastError) {
    }
}
