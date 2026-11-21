package gg.moonrise.hawkeye.core.service.importer;

import static gg.moonrise.hawkeye.core.repository.coreprotect.CoreProtectFixture.at;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.core.repository.coreprotect.CoreProtectFixture;
import gg.moonrise.hawkeye.core.repository.coreprotect.CoreProtectFixture.Layout;
import gg.moonrise.hawkeye.core.service.network.NetworkService;
import gg.moonrise.hawkeye.core.storage.event.EventStore;
import gg.moonrise.hawkeye.core.storage.importer.ImportStore;
import gg.moonrise.hawkeye.core.storage.shard.StorageManager;
import gg.moonrise.hawkeye.core.storage.shard.StorageState;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.hawkeye.entity.event.PendingEvent;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.entity.importer.ImportState;
import gg.moonrise.hawkeye.entity.importer.SkipReason;
import gg.moonrise.hawkeye.entity.query.Area;
import gg.moonrise.hawkeye.entity.query.LookupQuery;
import gg.moonrise.hawkeye.entity.query.ShardScope;
import gg.moonrise.hawkeye.support.TestHawkeyePlugin;
import gg.moonrise.hawkeye.support.TestPlugins;
import gg.moonrise.hawkeye.util.BlockPositions;
import gg.moonrise.hawkeye.util.EventIds;
import java.io.Serial;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.sqlite.JDBC;

/** The import end to end: a SQLite CoreProtect database into the plugin's own SQLite storage, on MockBukkit. */
public class ImportServiceTest {

    private static final String NODE = "server-coreprotect";

    @TempDir
    private Path folder;

    private TestHawkeyePlugin plugin;
    private ImportService service;
    private StorageState.Ready ready;

    @BeforeEach
    public void setUp() {
        MockBukkit.mock();
        plugin = TestPlugins.load();
        ready = TestPlugins.awaitStorage();
        service = TestPlugins.bean(ImportService.class);
        service.tune(0, ImportService.PROGRESS_INTERVAL.toMillis());
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void everyRowKindIsImportedIntoItsOwnNode() throws Exception {
        Path source = scenario("cp", "25.0");
        List<ImportProgress> started = new ArrayList<>();

        ImportOutcome outcome = run(source.toString(), new ImportListener() {
            @Override
            public void started(@NotNull ImportProgress progress) {
                started.add(progress);
            }
        });

        ImportOutcome.Finished finished = assertInstanceOf(ImportOutcome.Finished.class, outcome);
        assertEquals(ImportScenario.EVENTS, finished.totals().imported());
        assertEquals(ImportScenario.EVENTS, finished.totals().importedTotal());
        assertEquals(ImportScenario.SKIPPED, ImportScenario.skipped(finished.totals()));
        assertEquals(21, finished.totals().rowsRead());
        assertEquals(100, finished.totals().percent());
        assertEquals(1, started.size());
        assertFalse(started.getFirst().resumed());
        assertTrue(started.getFirst().source().startsWith("sqlite "), started.getFirst().source());

        ImportScenario.verify(ImportScenario.of(NODE));
        assertEquals(ImportScenario.EVENTS, ImportScenario.all().size(), "nothing else was written");

        ImportState state = TestPlugins.bean(ImportStore.class).load(ready.home(), "coreprotect:server:"
                + (ImportScenario.T - 100) + ":25.0").orElseThrow();
        assertTrue(state.complete());
        assertEquals(ImportScenario.EVENTS, state.imported());
        assertEquals(ImportScenario.SKIPPED, state.skipped().entrySet().stream().filter(entry -> entry.getValue() > 0)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
    }

    @Test
    public void importedEventsAreFoundByOrdinaryLookups() throws Exception {
        run(scenario("cp", "25.0").toString(), ImportListener.NONE);

        List<StoredEvent> atSign = TestPlugins.bean(EventStore.class).find(ready.home(),
                LookupQuery.at(new Area.Block("world", ImportScenario.SIGN_X, 64, 7)), new ShardScope.Scopes(
                        Set.of(TestPlugins.bean(StorageManager.class).worldScope())), 0, 10, true);
        assertEquals(List.of(Action.BLOCK_BREAK, Action.SIGN_EDIT, Action.BLOCK_PLACE),
                atSign.stream().map(StoredEvent::action).toList());
        long shears = TestPlugins.bean(EventStore.class).count(ready.home(), LookupQuery.at(new Area.Everywhere())
                .withActions(Set.of(Action.ENTITY_SHEAR)), new ShardScope.All());
        assertEquals(1, shears);
    }

    @Test
    public void existingEventsAndNamesAreLeftAlone() throws Exception {
        Actor renamed = Actor.player(ImportScenario.ALEX, "Alexander");
        List<PendingEvent> live = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            live.add(new PendingEvent(EventIds.stamp((ImportScenario.T + i) * 1000L, 0), "world",
                    BlockPositions.pack(1, 64, 1), renamed, Action.BLOCK_BREAK, "minecraft:dirt", "minecraft:air", 0,
                    new byte[] {(byte) i}));
        }
        TestPlugins.bean(EventStore.class).write(ready.home(), ready.node(),
                TestPlugins.bean(StorageManager.class).worldScope(), live);
        Map<Long, String> before = described(ImportScenario.all());

        assertInstanceOf(ImportOutcome.Finished.class, run(scenario("cp", "25.0").toString(), ImportListener.NONE));

        List<StoredEvent> after = ImportScenario.all();
        assertEquals(5 + ImportScenario.EVENTS, after.size());
        Map<Long, String> kept = described(after.stream().filter(event -> before.containsKey(event.id())).toList());
        assertEquals(before, kept, "the live events are untouched");
        assertTrue(after.stream().filter(event -> event.actor().uuid().isPresent())
                .allMatch(event -> event.actor().name().equals("Alexander")), "the newer name is kept");
        assertEquals(ImportScenario.EVENTS, ImportScenario.of(NODE).size());
    }

    @Test
    public void aSecondRunImportsOnlyNewRows() throws Exception {
        Path file = folder.resolve("cp").resolve("database.db");
        Files.createDirectories(file.getParent());
        try (CoreProtectFixture db = CoreProtectFixture.sqlite(file, Layout.CURRENT)) {
            ImportScenario.populate(db, "25.0");
            assertInstanceOf(ImportOutcome.Finished.class, run(file.toString(), ImportListener.NONE));

            ImportOutcome.Finished again = assertInstanceOf(ImportOutcome.Finished.class,
                    run(file.toString(), ImportListener.NONE));
            assertEquals(0, again.totals().imported());
            assertEquals(0, again.totals().rowsRead());
            assertEquals(ImportScenario.EVENTS, again.totals().importedTotal());
            assertEquals(ImportScenario.EVENTS, ImportScenario.of(NODE).size());

            db.chat(ImportScenario.T + 100, 1, at(1, 0, 70, 0), "later");
            db.session(ImportScenario.T + 101, 1, at(1, 0, 70, 0), 0);
            List<ImportProgress> started = new ArrayList<>();
            ImportOutcome.Finished caughtUp = assertInstanceOf(ImportOutcome.Finished.class, run(file.toString(),
                    new ImportListener() {
                        @Override
                        public void started(@NotNull ImportProgress progress) {
                            started.add(progress);
                        }
                    }));
            assertTrue(started.getFirst().resumed());
            assertEquals(2, caughtUp.totals().imported());
            assertEquals(2, caughtUp.totals().rowsRead());
            assertEquals(ImportScenario.EVENTS + 2, caughtUp.totals().importedTotal());
        }
        List<StoredEvent> events = ImportScenario.of(NODE);
        assertEquals(ImportScenario.EVENTS + 2, events.size());
        assertEquals(List.of(Action.CHAT, Action.QUIT), events.subList(events.size() - 2, events.size()).stream()
                .map(StoredEvent::action).toList());
        assertEquals(events.size(), events.stream().map(StoredEvent::id).distinct().count());
    }

    @Test
    public void rowsLoggedAfterTheRunBeganAreLeftForTheNextRun() throws Exception {
        Path file = folder.resolve("cp").resolve("database.db");
        Files.createDirectories(file.getParent());
        try (CoreProtectFixture db = CoreProtectFixture.sqlite(file, Layout.CURRENT)) {
            ImportScenario.populate(db, "25.0");
            // CoreProtect is still logging: a row stamped later than the start of the run, and behind it in
            // the table a row the consumer wrote late.
            long soon = System.currentTimeMillis() / 1000 + 3_600;
            db.chat(soon, 1, at(1, 0, 70, 0), "written during the import");
            db.chat(ImportScenario.T + 50, 1, at(1, 0, 70, 0), "queued late");

            ImportOutcome.Finished first = assertInstanceOf(ImportOutcome.Finished.class,
                    run(file.toString(), ImportListener.NONE));
            assertEquals(ImportScenario.EVENTS, first.totals().imported());
            assertEquals(ImportScenario.SKIPPED, ImportScenario.skipped(first.totals()), "left unread, not skipped");
            assertEquals(2, first.totals().rowsRemaining(), "the rows left for the next run are reported");

            ImportOutcome.Finished again = assertInstanceOf(ImportOutcome.Finished.class,
                    run(file.toString(), ImportListener.NONE));
            assertEquals(0, again.totals().rowsRead(), "still newer than this run: neither read nor skipped");
        }
        assertEquals(ImportScenario.EVENTS, ImportScenario.of(NODE).size());
    }

    @Test
    public void rowsWithinTheSettleTimeAreLeftForALaterRun() throws Exception {
        Path file = folder.resolve("cp").resolve("database.db");
        Files.createDirectories(file.getParent());
        long now = System.currentTimeMillis() / 1000;
        try (CoreProtectFixture db = CoreProtectFixture.sqlite(file, Layout.CURRENT)) {
            int world = minimal(db);
            db.chat(now - 600, 1, at(world, 0, 64, 0), "settled");
            db.chat(now - 500, 1, at(world, 0, 64, 0), "settled too");
            db.chat(now - 30, 1, at(world, 0, 64, 0), "maybe still being written");
            db.session(now - 20, 1, at(world, 0, 64, 0), 1);
        }
        service.settle(120);

        ImportOutcome.Finished first = assertInstanceOf(ImportOutcome.Finished.class, run(file.toString(),
                ImportListener.NONE));
        assertEquals(2, first.totals().imported());
        assertEquals(2, first.totals().rowsRead(), "newer than start - settle: not read");
        assertEquals(2, first.totals().rowsRemaining());
        assertEquals(0, first.totals().skippedTotal());

        service.settle(0);
        ImportOutcome.Finished later = assertInstanceOf(ImportOutcome.Finished.class, run(file.toString(),
                ImportListener.NONE));
        assertEquals(2, later.totals().imported());
        assertEquals(0, later.totals().rowsRemaining());
        assertEquals(100, later.totals().percent());
        assertEquals(4, ImportScenario.of(NODE).size());
    }

    @Test
    public void aRowStampedFarInTheFutureIsSkippedWithoutHoldingItsTableBack() throws Exception {
        Path file = folder.resolve("cp").resolve("database.db");
        Files.createDirectories(file.getParent());
        try (CoreProtectFixture db = CoreProtectFixture.sqlite(file, Layout.CURRENT)) {
            ImportScenario.populate(db, "25.0");
            long wrongClock = System.currentTimeMillis() / 1000 + 30L * 24 * 3_600;
            db.chat(wrongClock, 1, at(1, 0, 70, 0), "from a clock that ran ahead");
            db.chat(ImportScenario.T + 50, 1, at(1, 0, 70, 0), "behind it");

            ImportOutcome.Finished done = assertInstanceOf(ImportOutcome.Finished.class,
                    run(file.toString(), ImportListener.NONE));
            assertEquals(ImportScenario.EVENTS + 1, done.totals().imported());
            assertEquals(ImportScenario.SKIPPED.get(SkipReason.BEFORE_EPOCH) + 1,
                    done.totals().skipped(SkipReason.BEFORE_EPOCH));
        }
        List<StoredEvent> events = ImportScenario.of(NODE);
        assertEquals(ImportScenario.EVENTS + 1, events.size());
        assertTrue(events.stream().allMatch(event -> event.epochMillis() <= System.currentTimeMillis()),
                "nothing is stored in the future");
    }

    @Test
    public void aSqlitePurgeThatRenumberedATableLosesNoRows() throws Exception {
        Path file = folder.resolve("cp").resolve("database.db");
        Files.createDirectories(file.getParent());
        try (CoreProtectFixture db = CoreProtectFixture.sqlite(file, Layout.CURRENT)) {
            int world = minimal(db);
            for (int i = 0; i < 10; i++) {
                db.chat(ImportScenario.T + 10 + i, 1, at(world, 0, 64, 0), "old " + i);
            }
            assertEquals(10, assertInstanceOf(ImportOutcome.Finished.class, run(file.toString(),
                    ImportListener.NONE)).totals().imported());

            // /co purge on SQLite: chat rebuilt with its 7 newest rows, numbered 1 to 7; then 5 new rows (8 to
            // 12), so the stored cursor's rowid 10 now names a new row.
            db.execute("CREATE TABLE kept AS SELECT * FROM co_chat WHERE time >= " + (ImportScenario.T + 13)
                    + " ORDER BY rowid");
            db.execute("DELETE FROM co_chat");
            db.execute("INSERT INTO co_chat SELECT * FROM kept ORDER BY rowid");
            db.execute("DROP TABLE kept");
            for (int i = 0; i < 5; i++) {
                db.execute("INSERT INTO co_chat (time, user, wid, x, y, z, message) VALUES ("
                        + (ImportScenario.T + 30 + i) + ", 1, " + world + ", 0, 64, 0, 'new " + i + "')");
            }

            ImportOutcome.Finished after = assertInstanceOf(ImportOutcome.Finished.class, run(file.toString(),
                    ImportListener.NONE));
            assertEquals(5, after.totals().rowsRead(), "exactly the new rows");
            assertEquals(5, after.totals().imported());
            assertEquals(0, after.totals().displaced());
        }
        List<StoredEvent> events = ImportScenario.of(NODE);
        assertEquals(15, events.size());
        assertEquals(15, events.stream().map(StoredEvent::epochMillis).distinct().count(), "none twice");
    }

    @Test
    public void aPurgedBlockTableKeepsItsRowIdsAndContinuesFromItsCursor() throws Exception {
        Path file = folder.resolve("cp").resolve("database.db");
        Files.createDirectories(file.getParent());
        try (CoreProtectFixture db = CoreProtectFixture.sqlite(file, Layout.CURRENT)) {
            int world = minimal(db);
            int stone = db.material("minecraft:stone");
            for (int i = 0; i < 10; i++) {
                // Row 8 was logged late, stamped after every other row.
                long time = ImportScenario.T + (i == 7 ? 25 : 10 + i);
                db.block(time, 1, at(world, i, 64, 0), stone, 0, null, 1, 0);
            }
            assertEquals(10, assertInstanceOf(ImportOutcome.Finished.class, run(file.toString(),
                    ImportListener.NONE)).totals().imported());

            // /co purge keeps block's rowids: everything before T + 20 goes, the cursor row 10 with it.
            db.execute("DELETE FROM co_block WHERE time < " + (ImportScenario.T + 20));
            db.block(ImportScenario.T + 30, 1, at(world, 20, 64, 0), stone, 0, null, 1, 0);
            db.block(ImportScenario.T + 31, 1, at(world, 21, 64, 0), stone, 0, null, 1, 0);

            ImportOutcome.Finished after = assertInstanceOf(ImportOutcome.Finished.class, run(file.toString(),
                    ImportListener.NONE));
            assertEquals(2, after.totals().rowsRead(), "row 8 is not read again");
            assertEquals(2, after.totals().imported());
        }
        assertEquals(12, ImportScenario.of(NODE).size());
    }

    @Test
    public void aCancelledImportResumesToTheSameEvents() throws Exception {
        Path whole = busy("whole", "25.0");
        Path parts = busy("parts", "25.1");
        service.tune(20, 0);

        ImportOutcome.Finished uninterrupted = assertInstanceOf(ImportOutcome.Finished.class,
                run(whole.toString(), ImportListener.NONE));
        AtomicInteger reports = new AtomicInteger();
        ImportOutcome.Cancelled cancelled = assertInstanceOf(ImportOutcome.Cancelled.class, run(parts.toString(),
                new ImportListener() {
                    @Override
                    public void progress(@NotNull ImportProgress progress) {
                        reports.incrementAndGet();
                        assertTrue(service.cancel());
                    }
                }));
        assertEquals(1, reports.get());
        assertTrue(cancelled.totals().imported() > 0 && cancelled.totals().imported() < 300,
                cancelled.totals().toString());
        ImportOutcome.Finished resumed = assertInstanceOf(ImportOutcome.Finished.class,
                run(parts.toString(), ImportListener.NONE));

        assertEquals(300, uninterrupted.totals().imported());
        assertEquals(300, cancelled.totals().imported() + resumed.totals().imported());
        assertEquals(300, resumed.totals().importedTotal());
        assertEquals(cancelled.totals().rowsRead() + resumed.totals().rowsRead(), uninterrupted.totals().rowsRead());
        List<String> first = ImportScenario.of(NODE).stream().map(ImportServiceTest::withoutNode).toList();
        List<String> second = ImportScenario.of(NODE + "-2").stream().map(ImportServiceTest::withoutNode).toList();
        assertEquals(300, first.size());
        assertEquals(first, second);
    }

    @Test
    public void aSecondImportIsRefusedWhileOneRuns() throws Exception {
        Path source = scenario("cp", "25.0");
        CountDownLatch hold = new CountDownLatch(1);
        CompletableFuture<ImportOutcome> first = service.start(source.toString(), new ImportListener() {
            @Override
            public void started(@NotNull ImportProgress progress) {
                try {
                    hold.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        try {
            assertTrue(service.running());
            assertEquals(new ImportOutcome.AlreadyRunning(), service.start(source.toString(), ImportListener.NONE)
                    .get(5, TimeUnit.SECONDS));
        } finally {
            hold.countDown();
        }
        assertInstanceOf(ImportOutcome.Finished.class, first.get(20, TimeUnit.SECONDS));
        assertFalse(service.running());
        assertTrue(service.progress().isEmpty());
        assertFalse(service.cancel());
    }

    @Test
    public void anErrorOnTheImportThreadEndsTheRunAsFailed() throws Exception {
        Path source = scenario("cp", "25.0");

        // The listener's Error is not caught on its way up (ImportRun only catches exceptions), so it reaches
        // the service's own guard as an Error would from anywhere in the run.
        ImportOutcome.Failed failed = assertInstanceOf(ImportOutcome.Failed.class, run(source.toString(),
                new ImportListener() {
                    @Override
                    public void started(@NotNull ImportProgress progress) {
                        throw new Failure();
                    }
                }));

        assertEquals("Failure", failed.reason());
        assertFalse(service.running());
        assertTrue(service.progress().isEmpty());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (importThreads() > 0 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(0, importThreads(), "both executors are shut down");
        assertInstanceOf(ImportOutcome.Finished.class, run(source.toString(), ImportListener.NONE),
                "the next import starts");
    }

    @Test
    public void duckDbIsRefusedWhileCoreProtectRuns() throws Exception {
        Path file = Files.createFile(folder.resolve("database.duckdb"));
        MockBukkit.createMockPlugin("CoreProtect");

        assertEquals(new ImportOutcome.CoreProtectRunning(), run(file.toString(), ImportListener.NONE));
    }

    @Test
    public void missingAndInvalidSourcesAreReported() throws Exception {
        Path nowhere = folder.resolve("nowhere");
        assertEquals(new ImportOutcome.SourceMissing(nowhere.toString()), run(nowhere.toString(),
                ImportListener.NONE));
        Path plugins = plugin.getDataPath().toAbsolutePath().getParent();
        assertEquals(new ImportOutcome.SourceMissing(plugins.resolve("CoreProtect").toString()),
                run(null, ImportListener.NONE));

        Path config = folder.resolve("config.yml");
        Files.writeString(config, "database-type: oracle\n");
        ImportOutcome.SourceInvalid invalid = assertInstanceOf(ImportOutcome.SourceInvalid.class,
                run(config.toString(), ImportListener.NONE));
        assertTrue(invalid.reason().contains("oracle"), invalid.reason());
    }

    @Test
    public void aFileThatIsNotACoreProtectDatabaseCreatesNothing() throws Exception {
        Path empty = Files.createFile(folder.resolve("empty.db"));
        Path other = folder.resolve("other.db");
        try (Connection connection = new JDBC().connect("jdbc:sqlite:" + other, new Properties());
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE players (id INTEGER PRIMARY KEY, name TEXT)");
        }

        for (Path file : List.of(empty, other)) {
            ImportOutcome.Failed failed = assertInstanceOf(ImportOutcome.Failed.class, run(file.toString(),
                    ImportListener.NONE));
            assertTrue(failed.reason().startsWith("not a CoreProtect database: no table co_block"), failed.reason());
        }

        assertEquals(0, count("SELECT COUNT(*) FROM " + ready.home().database().tables().importState()));
        assertEquals(0, count("SELECT COUNT(*) FROM " + ready.home().database().tables().server()
                + " WHERE name LIKE '%" + ImportStore.NODE_SUFFIX + "%'"));
        assertTrue(ImportScenario.all().isEmpty());
    }

    @Test
    public void aMigratingSourceIsRefused() throws Exception {
        Path file = folder.resolve("database.db");
        try (CoreProtectFixture db = CoreProtectFixture.sqlite(file, Layout.CURRENT)) {
            ImportScenario.populate(db, "25.0");
            db.lockStatus(2);
        }
        assertEquals(new ImportOutcome.SourceMigrating(), run(file.toString(), ImportListener.NONE));
        assertTrue(ImportScenario.all().isEmpty());
    }

    @Test
    public void theImportNodeIsHiddenFromTheNetworkView() throws Exception {
        run(scenario("cp", "25.0").toString(), ImportListener.NONE);

        List<String> servers = TestPlugins.bean(NetworkService.class).view().get(20, TimeUnit.SECONDS).servers()
                .stream().map(NetworkService.NetworkServer::name).toList();
        assertEquals(List.of(ready.node().name()), servers);
        assertTrue(ImportScenario.all().stream().allMatch(event -> event.server().equals(NODE)));
    }

    /** A folder holding the scenario as {@code database.db}. */
    private @NotNull Path scenario(@NotNull String name, @NotNull String version) throws Exception {
        Path dir = Files.createDirectories(folder.resolve(name));
        try (CoreProtectFixture db = CoreProtectFixture.sqlite(dir.resolve("database.db"), Layout.CURRENT)) {
            ImportScenario.populate(db, version);
        }
        return dir;
    }

    /** 300 rows in 100 seconds, three tables, three rows a second, in time order. */
    private @NotNull Path busy(@NotNull String name, @NotNull String version) throws Exception {
        Path file = Files.createDirectories(folder.resolve(name)).resolve("database.db");
        try (CoreProtectFixture db = CoreProtectFixture.sqlite(file, Layout.CURRENT)) {
            db.version(ImportScenario.T, version);
            int alex = db.user("Alex", ImportScenario.ALEX.toString());
            int world = db.world("world");
            int stone = db.material("minecraft:stone");
            for (int second = 0; second < 100; second++) {
                long time = ImportScenario.T + 10 + second;
                db.block(time, alex, at(world, second, 64, 0), stone, 0, null, second % 2, 0);
                db.chat(time, alex, at(world, 0, 64, 0), "line " + second);
                db.block(time, alex, at(world, 0, 64, second), stone, 0, null, 1, 0);
            }
        }
        return file;
    }

    /** The install row, Alex as user 1 and the world; returns the world's id. */
    private static int minimal(@NotNull CoreProtectFixture db) throws SQLException {
        db.version(ImportScenario.T, "25.0");
        db.user("Alex", ImportScenario.ALEX.toString());
        return db.world("world");
    }

    private @NotNull ImportOutcome run(@Nullable String source, @NotNull ImportListener listener) throws Exception {
        return service.start(source, listener).get(30, TimeUnit.SECONDS);
    }

    private static @NotNull Map<Long, String> described(@NotNull List<StoredEvent> events) {
        return events.stream().collect(Collectors.toMap(StoredEvent::id, event -> event.server() + " "
                + ImportScenario.describe(event)));
    }

    private static @NotNull String withoutNode(@NotNull StoredEvent event) {
        return EventIds.slot(event.id()) + "/" + EventIds.rank(event.id()) + " " + ImportScenario.describe(event);
    }

    private long count(@NotNull String sql) throws SQLException {
        return ready.home().database().read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql);
                    ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        });
    }

    private static long importThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.isAlive() && thread.getName().startsWith("Hawkeye-Import"))
                .count();
    }

    /** An error such as running out of memory on the import thread. */
    private static final class Failure extends Error {

        @Serial
        private static final long serialVersionUID = 1L;
    }

}
