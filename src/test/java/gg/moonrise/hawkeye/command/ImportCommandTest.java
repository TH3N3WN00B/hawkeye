package gg.moonrise.hawkeye.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.config.permission.Permissions;
import gg.moonrise.hawkeye.core.repository.coreprotect.source.CoreProtectFixture;
import gg.moonrise.hawkeye.core.repository.coreprotect.source.CoreProtectFixture.Layout;
import gg.moonrise.hawkeye.core.service.importer.ImportListener;
import gg.moonrise.hawkeye.core.service.importer.ImportOutcome;
import gg.moonrise.hawkeye.core.service.importer.ImportProgress;
import gg.moonrise.hawkeye.core.service.importer.ImportScenario;
import gg.moonrise.hawkeye.core.service.importer.ImportService;
import gg.moonrise.hawkeye.support.TestCommandManager;
import gg.moonrise.hawkeye.support.TestHawkeyePlugin;
import gg.moonrise.hawkeye.support.TestPlugins;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.command.ConsoleCommandSenderMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

public class ImportCommandTest {

    private static final String SKIPPED = "Skipped 6 row(s): 2 older than 2025-01-01, which Hawkeye cannot store; 1 "
            + "with no Hawkeye equivalent; 2 sign snapshots kept in other events; 1 with an unknown world, material, "
            + "entity or position.";

    @TempDir
    private Path folder;

    private ServerMock server;
    private TestHawkeyePlugin plugin;
    private TestCommandManager commands;
    private ConsoleCommandSenderMock console;

    @BeforeEach
    public void setUp() {
        server = MockBukkit.mock();
        plugin = TestPlugins.load();
        TestPlugins.awaitStorage();
        console = server.getConsoleSender();
        commands = RollbackTestSupport.commands();
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void theConsoleImportsADatabase() throws Exception {
        Path source = scenario();

        commands.run(console, "hawkeye import coreprotect " + source);

        List<String> replies = RollbackTestSupport.tickUntilReply(server, console, "Skipped");
        assertEquals("Importing CoreProtect history from sqlite " + source.resolve("database.db") + ". Use "
                + "/he import status to follow it.", replies.getFirst());
        assertTrue(replies.get(1).startsWith("Import done: 15 event(s) imported from sqlite "), replies.toString());
        assertTrue(replies.get(1).endsWith(" (15 from this database in all)."), replies.toString());
        assertEquals(SKIPPED, replies.get(2));
        assertEquals(3, replies.size(), replies.toString());
        assertEquals(ImportScenario.EVENTS, ImportScenario.all().size());
    }

    @Test
    public void rowsLeftForTheNextRunAreNamed() throws Exception {
        Path source = scenario();
        try (CoreProtectFixture db = CoreProtectFixture.sqlite(source.resolve("database.db"), Layout.CURRENT)) {
            // Logged while the import runs: stamped after its start.
            long soon = System.currentTimeMillis() / 1000 + 3_600;
            db.execute("INSERT INTO {prefix}chat (time, user, wid, x, y, z, message) VALUES (" + soon
                    + ", 1, 1, 0, 70, 0, 'still logging')");
        }

        commands.run(console, "hawkeye import coreprotect " + source);

        List<String> replies = RollbackTestSupport.tickUntilReply(server, console, "Skipped");
        assertTrue(replies.get(1).startsWith("Import done: 15 event(s) imported from sqlite "), replies.toString());
        assertEquals("About 1 row(s) were logged too recently or while the import ran; the next run of "
                + "/he import coreprotect imports them.", replies.get(2));
        assertEquals(SKIPPED, replies.get(3));
        assertEquals(4, replies.size(), replies.toString());
    }

    @Test
    public void runningItAgainSaysWhatWasImportedBefore() throws Exception {
        Path source = scenario();
        commands.run(console, "he import coreprotect " + source.resolve("database.db"));
        RollbackTestSupport.tickUntilReply(server, console, "Import done");
        TestPlugins.drain(console);

        commands.run(console, "he import coreprotect " + source.resolve("database.db"));

        List<String> replies = RollbackTestSupport.tickUntilReply(server, console, "Import done");
        assertEquals("Continuing the CoreProtect import from sqlite " + source.resolve("database.db") + ": 15 "
                + "event(s) were imported before.", replies.getFirst());
        assertTrue(replies.get(1).startsWith("Import done: 0 event(s)"), replies.toString());
        assertEquals(2, replies.size(), "nothing was skipped this time: " + replies);
    }

    @Test
    public void aMissingSourceIsNamed() {
        Path nowhere = folder.resolve("nowhere");

        commands.run(console, "hawkeye import coreprotect " + nowhere);

        RollbackTestSupport.tickUntilReply(server, console, "No CoreProtect database found at " + nowhere + ".");
    }

    @Test
    public void withoutASourceCoreProtectsFolderIsUsed() {
        commands.run(console, "hawkeye import coreprotect");

        Path expected = plugin.getDataPath().toAbsolutePath().getParent().resolve("CoreProtect");
        RollbackTestSupport.tickUntilReply(server, console, "No CoreProtect database found at " + expected + ".");
    }

    @Test
    public void statusAndCancelWithoutAnImport() {
        commands.run(console, "hawkeye import status");
        assertEquals(List.of("No import is running."), RollbackTestSupport.tickUntilReply(server, console,
                "No import"));
        commands.run(console, "hawkeye import cancel");
        assertEquals(List.of("No import is running."), RollbackTestSupport.tickUntilReply(server, console,
                "No import"));
    }

    @Test
    public void statusAndCancelOfARunningImport() throws Exception {
        Path source = scenario();
        ImportService service = TestPlugins.bean(ImportService.class);
        CountDownLatch opened = new CountDownLatch(1);
        CountDownLatch hold = new CountDownLatch(1);
        CompletableFuture<ImportOutcome> running = service.start(source.toString(), new ImportListener() {
            @Override
            public void started(@NotNull ImportProgress progress) {
                opened.countDown();
                await(hold);
            }
        });
        try {
            assertTrue(opened.await(20, TimeUnit.SECONDS));
            commands.run(console, "hawkeye import status");
            String status = RollbackTestSupport.tickUntilReply(server, console, "Importing from").getFirst();
            assertTrue(status.startsWith("Importing from sqlite " + source.resolve("database.db") + " for "), status);
            assertTrue(status.endsWith(": 0 event(s) (0 in all), 0% read, 0 events/s."), status);

            commands.run(console, "he import cancel");
            assertEquals(List.of("Stopping the import after the current batch..."),
                    RollbackTestSupport.tickUntilReply(server, console, "Stopping"));
        } finally {
            hold.countDown();
        }
        ImportOutcome.Cancelled cancelled = assertInstanceOf(ImportOutcome.Cancelled.class,
                running.get(20, TimeUnit.SECONDS));
        assertEquals(0, cancelled.totals().imported());
    }

    @Test
    public void anImportWhileOneRunsIsRefused() throws Exception {
        Path source = scenario();
        CountDownLatch hold = new CountDownLatch(1);
        CompletableFuture<ImportOutcome> running = TestPlugins.bean(ImportService.class).start(source.toString(),
                new ImportListener() {
                    @Override
                    public void started(@NotNull ImportProgress progress) {
                        await(hold);
                    }
                });
        try {
            commands.run(console, "hawkeye import coreprotect " + source);
            RollbackTestSupport.tickUntilReply(server, console, "An import is already running; see /he import "
                    + "status.");
        } finally {
            hold.countDown();
        }
        running.get(20, TimeUnit.SECONDS);
    }

    @Test
    public void playersNeedThePermission() throws Exception {
        Path source = scenario();
        PlayerMock steve = server.addPlayer("Steve");
        TestPlugins.drain(steve);
        for (String command : List.of("he import coreprotect " + source, "he import status", "he import cancel")) {
            try {
                commands.run(steve, command);
            } catch (CompletionException refused) {
                // Cloud refuses the command for lack of permission.
            }
        }
        server.getScheduler().performTicks(5);
        assertTrue(TestPlugins.drain(steve).stream().noneMatch(line -> line.contains("mport")));
        assertEquals(0, ImportScenario.all().stream().filter(event -> event.server().endsWith("-coreprotect"))
                .count());

        steve.addAttachment(plugin, Permissions.IMPORT, true);
        commands.run(steve, "he import status");
        RollbackTestSupport.tickUntilReply(server, steve, "No import is running.");
    }

    private @NotNull Path scenario() throws Exception {
        Path dir = Files.createDirectories(folder.resolve("CoreProtect"));
        try (CoreProtectFixture db = CoreProtectFixture.sqlite(dir.resolve("database.db"), Layout.CURRENT)) {
            ImportScenario.populate(db, "25.0");
        }
        return dir;
    }

    private static void await(@NotNull CountDownLatch latch) {
        try {
            latch.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
