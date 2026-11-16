package gg.moonrise.hawkeye.core.service.purge;

import gg.moonrise.hawkeye.core.storage.EventStore;
import gg.moonrise.hawkeye.core.storage.ShardScope;
import gg.moonrise.hawkeye.core.storage.StorageManager;
import gg.moonrise.hawkeye.core.storage.StorageState;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.hawkeye.entity.event.PendingEvent;
import gg.moonrise.hawkeye.entity.query.Area;
import gg.moonrise.hawkeye.entity.query.LookupQuery;
import gg.moonrise.hawkeye.support.TestPlugins;
import gg.moonrise.hawkeye.util.BlockPositions;
import gg.moonrise.hawkeye.util.EventIds;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.jetbrains.annotations.NotNull;

/** Writes events of a chosen age straight into the running plugin's home shard, for purge tests. */
public final class PurgeEvents {

    private static final Actor ALEX = Actor.player(UUID.fromString("00000000-0000-0000-0000-00000000000a"), "Alex");
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private PurgeEvents() {
    }

    /**
     * Writes block breaks of some age.
     *
     * @param age   how long ago they happened
     * @param world the world name
     * @param count how many
     * @throws SQLException when writing fails
     */
    public static void write(@NotNull Duration age, @NotNull String world, int count) throws SQLException {
        StorageState.Ready ready = TestPlugins.awaitStorage();
        long millis = System.currentTimeMillis() - age.toMillis();
        List<PendingEvent> events = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            events.add(new PendingEvent(EventIds.stamp(millis, SEQUENCE.getAndIncrement() & EventIds.MAX_SEQUENCE),
                    world, BlockPositions.pack(i, 64, 0), ALEX, Action.BLOCK_BREAK, "minecraft:stone", "minecraft:air",
                    0, null));
        }
        TestPlugins.bean(EventStore.class).write(ready.home(), ready.node(),
                TestPlugins.bean(StorageManager.class).worldScope(), events);
    }

    /**
     * Counts the events of a world (or all worlds).
     *
     * @param area where
     * @return the count
     * @throws SQLException when reading fails
     */
    public static long count(@NotNull Area area) throws SQLException {
        return TestPlugins.bean(EventStore.class).count(TestPlugins.awaitStorage().home(), LookupQuery.at(area),
                new ShardScope.All());
    }
}
