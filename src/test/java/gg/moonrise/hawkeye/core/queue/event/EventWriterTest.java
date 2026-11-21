package gg.moonrise.hawkeye.core.queue.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.core.queue.spool.EventSpool;
import gg.moonrise.hawkeye.core.service.capture.Actors;
import gg.moonrise.hawkeye.core.service.capture.EventRecorder;
import gg.moonrise.hawkeye.core.sql.Database;
import gg.moonrise.hawkeye.core.storage.event.EventStore;
import gg.moonrise.hawkeye.core.storage.shard.StorageManager;
import gg.moonrise.hawkeye.core.storage.shard.StorageState;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.event.PendingEvent;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.entity.query.Area;
import gg.moonrise.hawkeye.entity.query.LookupQuery;
import gg.moonrise.hawkeye.entity.query.ShardScope;
import gg.moonrise.hawkeye.entity.storage.WriterStats;
import gg.moonrise.hawkeye.support.TestHawkeyePlugin;
import gg.moonrise.hawkeye.support.TestPlugins;
import gg.moonrise.hawkeye.util.codec.SpoolCodec;
import gg.moonrise.hawkeye.util.event.EventIds;
import gg.moonrise.hawkeye.util.event.EventOrder;
import gg.moonrise.hawkeye.util.position.BlockPositions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** From the recorder through the queue and the writer thread into SQLite, as on a live server. */
public class EventWriterTest {

    private static final String TRIGGER = "writer_test_fail";

    @TempDir
    private Path saved;

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
    public void aFlushWritesEventsOfTheCurrentSecond() {
        StorageState.Ready ready = TestPlugins.awaitStorage();
        World world = server.addSimpleWorld("world");
        long before = slotNow();
        TestPlugins.bean(EventRecorder.class).record(Action.BLOCK_BREAK, Actors.NATURE, world, 3, 64, 3,
                "minecraft:stone", "minecraft:air", 0, null);
        TestPlugins.flush();
        long after = slotNow();

        List<Row> rows = rows(ready);
        assertEquals(1, rows.size());
        long slot = EventIds.slot(rows.getFirst().id());
        assertTrue(slot >= before && slot <= after, slot + " not in " + before + ".." + after);
        assertEquals(0, EventIds.rank(rows.getFirst().id()));
    }

    @Test
    public void writtenIdsAreRankedOnTheGridInTheFixedOrder() {
        StorageState.Ready ready = TestPlugins.awaitStorage();
        World world = server.addSimpleWorld("world");
        PlayerMock player = server.addPlayer("Alex");
        EventRecorder recorder = TestPlugins.bean(EventRecorder.class);
        List<Integer> xs = new ArrayList<>();
        for (int x = -20; x <= 20; x++) {
            xs.add(x);
        }
        Collections.shuffle(xs, new Random(7));
        for (int i = 0; i < xs.size(); i++) {
            recorder.record(Action.BLOCK_PLACE, Actors.of(player), world, xs.get(i), 64, -xs.get(i),
                    "minecraft:stone", "minecraft:air", 0, null);
            if (i % 10 == 0) {
                recorder.record(Action.CHAT, Actors.of(player), world, 100 - i, 70, 0, "", null, 0, null);
            }
        }
        TestPlugins.flush();

        List<Row> rows = rows(ready);
        assertEquals(xs.size() + 5 + 1, rows.size(), "placements, chat and the join");
        assertOnGrid(rows);
        for (List<Row> slot : bySlot(rows).values()) {
            for (int i = 1; i < slot.size(); i++) {
                Row previous = slot.get(i - 1);
                Row row = slot.get(i);
                boolean previousPlaced = EventOrder.byPlace(previous.action());
                boolean placed = EventOrder.byPlace(row.action());
                assertFalse(previousPlaced && !placed, "an unplaced event after a placed one: " + slot);
                if (previousPlaced) {
                    assertTrue(Long.compare(previous.position(), row.position()) <= 0, "positions out of order: "
                            + slot);
                }
            }
        }
    }

    @Test
    public void aRetriedBatchKeepsItsIds() throws Exception {
        StorageState.Ready ready = TestPlugins.awaitStorage();
        World world = server.addSimpleWorld("world");
        TestPlugins.flush();
        EventRecorder recorder = TestPlugins.bean(EventRecorder.class);
        EventWriter writer = TestPlugins.bean(EventWriter.class);
        execute(ready.home().database(), failInserts(ready));
        for (int x = 0; x < 10; x++) {
            recorder.record(Action.BLOCK_PLACE, Actors.NATURE, world, x, 64, 0, "minecraft:stone", "minecraft:air",
                    0, null);
        }
        writer.flush().get(20, TimeUnit.SECONDS);
        WriterStats failed = writer.stats();
        assertTrue(failed.failures() > 0, failed.toString());
        assertEquals(10, failed.queued(), failed.toString());

        execute(ready.home().database(), "DROP TRIGGER " + TRIGGER);
        // Smaller positions: had the failed batch been numbered again with these, they would rank first.
        for (int x = -10; x < 0; x++) {
            recorder.record(Action.BLOCK_PLACE, Actors.NATURE, world, x, 64, 0, "minecraft:stone", "minecraft:air",
                    0, null);
        }
        TestPlugins.flush();

        List<Row> rows = rows(ready);
        assertEquals(20, rows.size());
        assertOnGrid(rows);
        long firstGroupMax = rows.stream().filter(row -> row.x() >= 0).mapToLong(Row::id).max().orElseThrow();
        long secondGroupMin = rows.stream().filter(row -> row.x() < 0).mapToLong(Row::id).min().orElseThrow();
        assertTrue(firstGroupMax < secondGroupMin, rows.toString());
        assertEquals(0, writer.stats().queued());
    }

    @Test
    public void spooledEventsCarryFinalStampsAndReplayAsTheyAre() throws Exception {
        StorageState.Ready ready = TestPlugins.awaitStorage();
        World world = server.addSimpleWorld("world");
        TestPlugins.flush();
        execute(ready.home().database(), failInserts(ready));
        EventRecorder recorder = TestPlugins.bean(EventRecorder.class);
        for (int x = 4; x >= 0; x--) {
            recorder.record(Action.BLOCK_PLACE, Actors.NATURE, world, x, 64, 0, "minecraft:stone", "minecraft:air",
                    0, null);
        }

        stop();
        List<PendingEvent> spooled = spooled();
        assertEquals(5, spooled.size());
        List<Long> expectedIds = spooled.stream()
                .map(event -> EventIds.fromStamp(event.stamp(), ready.node().node())).sorted().toList();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + saved.resolve("hawkeye.db"));
                Statement statement = connection.createStatement()) {
            statement.execute("DROP TRIGGER " + TRIGGER);
        }
        start();

        StorageState.Ready restarted = TestPlugins.awaitStorage();
        assertEquals(ready.node().node(), restarted.node().node());
        TestPlugins.flush();
        List<Row> rows = rows(restarted);
        assertEquals(expectedIds, rows.stream().map(Row::id).toList());
        assertOnGrid(rows);
        assertEquals(List.of(0, 1, 2, 3, 4), rows.stream().map(Row::x).toList(), "ranked by position");
        assertTrue(spooledFiles().isEmpty());
    }

    @Test
    public void aSpoolFileThatCannotBeWrittenDoesNotHoldBackNewEvents() throws Exception {
        StorageState.Ready ready = TestPlugins.awaitStorage();
        World world = server.addSimpleWorld("world");
        TestPlugins.flush();
        execute(ready.home().database(), failInserts(ready));
        EventRecorder recorder = TestPlugins.bean(EventRecorder.class);
        for (int x = 0; x < 3; x++) {
            recorder.record(Action.BLOCK_PLACE, Actors.NATURE, world, x, 64, 0, "minecraft:stone", "minecraft:air",
                    0, null);
        }

        stop();
        long newestSpooled = spooled().stream()
                .mapToLong(event -> EventIds.fromStamp(event.stamp(), ready.node().node())).max().orElseThrow();
        // From now on only the spooled events fail to insert.
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + saved.resolve("hawkeye.db"));
                Statement statement = connection.createStatement()) {
            statement.execute("DROP TRIGGER " + TRIGGER);
            statement.execute("CREATE TRIGGER " + TRIGGER + " BEFORE INSERT ON "
                    + ready.home().database().sql().tables().event() + " WHEN NEW.id <= " + newestSpooled
                    + " BEGIN SELECT RAISE(ABORT, 'writer test'); END");
        }
        start();

        StorageState.Ready restarted = TestPlugins.awaitStorage();
        World again = server.addSimpleWorld("world");
        EventRecorder restartedRecorder = TestPlugins.bean(EventRecorder.class);
        restartedRecorder.record(Action.BLOCK_PLACE, Actors.NATURE, again, 50, 64, 0, "minecraft:stone",
                "minecraft:air", 0, null);
        TestPlugins.flush();

        List<Row> placed = rows(restarted).stream().filter(row -> row.x() == 50).toList();
        assertEquals(1, placed.size(), "the new event is stored although the spool is stuck");
        assertTrue(EventIds.slot(placed.getFirst().id()) > EventIds.slot(newestSpooled),
                "new ids start past every spooled slot");
        assertEquals(1, spooledFiles().size(), "the spool file waits for its next attempt");
    }

    @Test
    public void afterARestartNumberingStartsPastTheNewestStoredSlot() throws Exception {
        StorageState.Ready ready = TestPlugins.awaitStorage();
        server.addSimpleWorld("world");
        TestPlugins.flush();
        long future = slotNow() + 50;
        TestPlugins.bean(EventStore.class).write(ready.home(), ready.node(),
                TestPlugins.bean(StorageManager.class).worldScope(),
                List.of(new PendingEvent(EventIds.slotStamp(future, 0), "world", BlockPositions.pack(0, 64, 0),
                        Actors.NATURE, Action.BLOCK_PLACE, "minecraft:stone", "minecraft:air", 0, null)));

        stop();
        start();
        TestPlugins.awaitStorage();
        World world = server.addSimpleWorld("world");
        TestPlugins.bean(EventRecorder.class).record(Action.BLOCK_PLACE, Actors.NATURE, world, 777, 64, 0,
                "minecraft:stone", "minecraft:air", 0, null);
        TestPlugins.flush();

        Row fresh = rows(TestPlugins.awaitStorage()).stream().filter(row -> row.x() == 777).findFirst()
                .orElseThrow();
        assertTrue(EventIds.slot(fresh.id()) > future, EventIds.slot(fresh.id()) + " <= " + future);
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

    /** Disables the plugin (the writer drains and spools) and saves its data folder. */
    private void stop() throws IOException {
        server.getPluginManager().disablePlugin(plugin);
        copy(plugin.getDataPath(), saved);
    }

    /** Starts a new server whose plugin folder holds what {@link #stop()} saved. */
    private void start() throws IOException {
        String folder = plugin.getDataPath().getFileName().toString();
        MockBukkit.unmock();
        server = MockBukkit.mock();
        // Every mocked server has its own temporary plugins folder: find it through a probe plugin.
        copy(saved, MockBukkit.createMockPlugin("Probe").getDataPath().resolveSibling(folder));
        plugin = TestPlugins.load();
    }

    private @NotNull List<PendingEvent> spooled() throws IOException {
        List<PendingEvent> events = new ArrayList<>();
        for (Path file : spooledFiles()) {
            events.addAll(SpoolCodec.decode(Files.readAllBytes(file)));
        }
        return events;
    }

    private @NotNull List<Path> spooledFiles() throws IOException {
        Path folder = plugin.getDataPath().resolve(EventSpool.FOLDER);
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(file -> file.toString().endsWith(".spool")).toList();
        }
    }

    private static void copy(@NotNull Path from, @NotNull Path to) throws IOException {
        try (Stream<Path> paths = Files.walk(from)) {
            for (Path path : paths.toList()) {
                Path target = to.resolve(from.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static @NotNull String failInserts(@NotNull StorageState.Ready ready) {
        return "CREATE TRIGGER " + TRIGGER + " BEFORE INSERT ON " + ready.home().database().sql().tables().event()
                + " BEGIN SELECT RAISE(ABORT, 'writer test'); END";
    }

    private static void execute(@NotNull Database database, @NotNull String statementSql) throws SQLException {
        database.transaction(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute(statementSql);
            }
            return null;
        });
    }

    /** Every hot row, in id order. */
    private static @NotNull List<Row> rows(@NotNull StorageState.Ready ready) {
        Database database = ready.home().database();
        try {
            return database.read(connection -> {
                try (PreparedStatement statement = connection.prepareStatement("SELECT id, pos, action FROM "
                        + database.sql().tables().event() + " ORDER BY id");
                        ResultSet result = statement.executeQuery()) {
                    List<Row> rows = new ArrayList<>();
                    while (result.next()) {
                        rows.add(new Row(result.getLong(1), result.getLong(2), result.getInt(3)));
                    }
                    return rows;
                }
            });
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Ranks count 0, 1, 2, ... in every slot. */
    private static void assertOnGrid(@NotNull List<Row> rows) {
        for (Map.Entry<Long, List<Row>> slot : bySlot(rows).entrySet()) {
            List<Integer> ranks = slot.getValue().stream().map(row -> EventIds.rank(row.id())).toList();
            List<Integer> expected = Stream.iterate(0, rank -> rank + 1).limit(ranks.size()).toList();
            assertEquals(expected, ranks, "ranks of slot " + slot.getKey());
        }
    }

    private static @NotNull Map<Long, List<Row>> bySlot(@NotNull List<Row> rows) {
        return rows.stream().collect(Collectors.groupingBy(row -> EventIds.slot(row.id()), TreeMap::new,
                Collectors.toList()));
    }

    private static long slotNow() {
        return (System.currentTimeMillis() - EventIds.EPOCH_MILLIS) / EventIds.SLOT_MILLIS;
    }

    /**
     * A stored row.
     *
     * @param id       the event id
     * @param position the packed position
     * @param action   the action code
     */
    private record Row(long id, long position, int action) {

        private int x() {
            return BlockPositions.x(position);
        }
    }
}
