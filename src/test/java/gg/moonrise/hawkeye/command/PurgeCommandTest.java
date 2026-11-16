package gg.moonrise.hawkeye.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.core.service.purge.PurgeEvents;
import gg.moonrise.hawkeye.entity.query.Area;
import gg.moonrise.hawkeye.support.TestCommandManager;
import gg.moonrise.hawkeye.support.TestHawkeyePlugin;
import gg.moonrise.hawkeye.support.TestPlugins;
import java.time.Duration;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.command.ConsoleCommandSenderMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

public class PurgeCommandTest {

    private static final String UNSUPPORTED = "Purge takes one age, e.g. t:30d, and optionally r:#<world>; nothing "
            + "else.";

    private ServerMock server;
    private TestHawkeyePlugin plugin;
    private TestCommandManager commands;
    private ConsoleCommandSenderMock console;
    private PlayerMock alex;

    @BeforeEach
    public void setUp() {
        server = MockBukkit.mock();
        plugin = TestPlugins.load();
        TestPlugins.awaitStorage();
        World world = server.addSimpleWorld("world");
        server.addSimpleWorld("nether");
        console = server.getConsoleSender();
        alex = server.addPlayer("Alex");
        alex.teleport(new Location(world, 0, 64, 0));
        alex.addAttachment(plugin, Permissions.PURGE, true);
        commands = RollbackTestSupport.commands();
        TestPlugins.drain(alex);
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void theConsolePurgesOldEvents() throws Exception {
        PurgeEvents.write(Duration.ofDays(40), "world", 4);
        PurgeEvents.write(Duration.ofDays(1), "world", 1);

        commands.run(console, "hawkeye purge t:30d");

        List<String> replies = RollbackTestSupport.tickUntilReply(server, console, "Purge done");
        assertEquals("Deleting events older than 4w2d...", replies.getFirst());
        assertTrue(replies.getLast().startsWith("Purge done: 4 event(s) deleted in "), replies.toString());
        assertEquals(1, PurgeEvents.count(new Area.Everywhere()));
    }

    @Test
    public void aPurgeCanTargetOneWorld() throws Exception {
        PurgeEvents.write(Duration.ofDays(40), "world", 4);
        PurgeEvents.write(Duration.ofDays(40), "nether", 2);

        commands.run(console, "hawkeye purge t:30d r:#nether");

        RollbackTestSupport.tickUntilReply(server, console, "Purge done: 2 event(s)");
        assertEquals(4, PurgeEvents.count(new Area.Everywhere()));
    }

    @Test
    public void playersOnlyPurgeOldData() throws Exception {
        PurgeEvents.write(Duration.ofDays(40), "world", 3);

        commands.run(alex, "hawkeye purge t:1d");
        RollbackTestSupport.tickUntilReply(server, alex, "In game you may only purge data older than 30 days.");
        assertEquals(3, PurgeEvents.count(new Area.Everywhere()));

        commands.run(alex, "he purge t:31d");
        RollbackTestSupport.tickUntilReply(server, alex, "Purge done: 3 event(s)");
    }

    @Test
    public void aPurgeNeedsATime() {
        commands.run(console, "hawkeye purge r:#world");
        assertEquals(List.of("Add a time, e.g. t:2h."), TestPlugins.drain(console));
    }

    @Test
    public void aPurgeTakesOnlyAnAgeAndAWorld() throws Exception {
        PurgeEvents.write(Duration.ofDays(40), "world", 3);
        for (String parameters : List.of("t:30d u:alex", "t:30d a:block", "t:1d-40d", "t:30d i:stone")) {
            commands.run(console, "hawkeye purge " + parameters);
            assertEquals(List.of(UNSUPPORTED), TestPlugins.drain(console), parameters);
        }
        commands.run(alex, "hawkeye purge t:31d r:10");
        assertEquals(List.of(UNSUPPORTED), TestPlugins.drain(alex));
        assertEquals(3, PurgeEvents.count(new Area.Everywhere()));
    }
}
