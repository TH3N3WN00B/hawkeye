package gg.moonrise.hawkeye.core.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.core.service.capture.Actors;
import gg.moonrise.hawkeye.core.service.capture.EventRecorder;
import gg.moonrise.hawkeye.core.storage.EventStore;
import gg.moonrise.hawkeye.core.storage.ShardScope;
import gg.moonrise.hawkeye.core.storage.StorageManager;
import gg.moonrise.hawkeye.core.storage.StorageState;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.entity.query.Area;
import gg.moonrise.hawkeye.entity.query.LookupQuery;
import gg.moonrise.hawkeye.support.TestHawkeyePlugin;
import gg.moonrise.hawkeye.support.TestPlugins;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** From the recorder through the queue and the writer thread into SQLite, as on a live server. */
public class EventWriterTest {

    private ServerMock server;
    private TestHawkeyePlugin plugin;

    @BeforeEach
    public void setUp() {
        server = MockBukkit.mock();
        plugin = TestPlugins.load();
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void aFlushWritesEverythingRecordedBeforeIt() throws Exception {
        StorageState.Ready ready = TestPlugins.bean(StorageManager.class).ready().get(20, TimeUnit.SECONDS);
        World world = server.addSimpleWorld("world");
        PlayerMock player = server.addPlayer("Alex");
        EventRecorder recorder = TestPlugins.bean(EventRecorder.class);
        for (int x = 0; x < 50; x++) {
            recorder.record(Action.BLOCK_PLACE, Actors.of(player), world, x, 64, 0, "minecraft:stone",
                    "minecraft:air", 0, null);
        }
        TestPlugins.bean(EventWriter.class).flush().get(20, TimeUnit.SECONDS);

        List<StoredEvent> events = TestPlugins.bean(EventStore.class).find(ready.home(),
                // The player's join is recorded at the spawn, inside this box: count only the placements.
                LookupQuery.at(new Area.Box("world", 0, 0, 0, 9, 100, 0)).withActions(Set.of(Action.BLOCK_PLACE)),
                new ShardScope.Scopes(Set.of("server")), 0, 100, true);
        assertEquals(10, events.size());
        assertTrue(events.stream().allMatch(event -> event.actor().name().equals("Alex")));
        WriterStats stats = TestPlugins.bean(EventWriter.class).stats();
        assertTrue(stats.written() >= 50, stats.toString());
        assertEquals(0, stats.queued(), stats.toString());
    }

    @Test
    public void disabledWorldsAreNotRecorded() throws Exception {
        TestPlugins.writeConfig(plugin, "logging:\n  disabled-worlds: [lobby]\n");
        TestPlugins.reload();
        World lobby = server.addSimpleWorld("lobby");
        EventRecorder recorder = TestPlugins.bean(EventRecorder.class);
        recorder.record(Action.BLOCK_PLACE, Actors.NATURE, lobby, 0, 64, 0, "minecraft:stone", "minecraft:air", 0,
                null);
        assertEquals(0, TestPlugins.bean(EventQueue.class).size());
    }
}
