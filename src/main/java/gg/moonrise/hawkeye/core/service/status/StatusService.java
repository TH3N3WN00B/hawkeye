package gg.moonrise.hawkeye.core.service.status;

import gg.moonrise.hawkeye.config.HawkeyeConfigProvider;
import gg.moonrise.hawkeye.core.queue.EventWriter;
import gg.moonrise.hawkeye.core.queue.WriterStats;
import gg.moonrise.hawkeye.core.storage.StorageManager;
import gg.moonrise.hawkeye.core.storage.StorageState;
import gg.moonrise.moss.spring.SpringComponent;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

/** Collects what {@code /hawkeye status} shows. */
@SpringComponent
@RequiredArgsConstructor
public final class StatusService {

    private final @NotNull StorageManager storage;
    private final @NotNull EventWriter writer;
    private final @NotNull HawkeyeConfigProvider configProvider;

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
