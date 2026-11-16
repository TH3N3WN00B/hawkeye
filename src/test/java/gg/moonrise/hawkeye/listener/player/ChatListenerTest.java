package gg.moonrise.hawkeye.listener.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.entity.payload.Payload;
import gg.moonrise.hawkeye.listener.Recorded;
import gg.moonrise.hawkeye.support.TestHawkeyePlugin;
import gg.moonrise.hawkeye.support.TestPlugins;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

public class ChatListenerTest {

    private ServerMock server;
    private TestHawkeyePlugin plugin;
    private PlayerMock player;

    @BeforeEach
    public void setUp() {
        server = MockBukkit.mock();
        plugin = TestPlugins.load();
        World world = server.addSimpleWorld("world");
        player = server.addPlayer("Alex");
        player.teleport(new Location(world, 7, 80, -7));
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void chatIsRecordedFromThePlayersThreadWhereTheyStand() {
        player.chat("hello there");
        server.getScheduler().waitAsyncEventsFinished();
        server.getScheduler().performOneTick();

        StoredEvent event = Recorded.only(Action.CHAT);
        assertEquals("Alex", event.actor().name());
        assertEquals(new Payload.Text("hello there"), event.payload().orElseThrow());
        assertEquals(7, event.x());
        assertEquals(80, event.y());
    }

    @Test
    public void disabledChatSchedulesNothing() {
        TestPlugins.writeConfig(plugin, "logging:\n  chat: false\n");
        TestPlugins.reload();
        player.chat("hello there");
        server.getScheduler().waitAsyncEventsFinished();
        server.getScheduler().performOneTick();

        assertTrue(Recorded.events(Action.CHAT).isEmpty());
    }

    @Test
    public void aCommandIsRecordedAsTypedAndARedactedOneByLabel() {
        command("/home set base");
        command("/login hunter2");

        assertEquals(new Payload.Text("/home set base"), Recorded.events(Action.COMMAND).get(0).payload()
                .orElseThrow());
        assertEquals(new Payload.Text("/login"), Recorded.events(Action.COMMAND).get(1).payload().orElseThrow());
    }

    private void command(String line) {
        server.getPluginManager().callEvent(new PlayerCommandPreprocessEvent(player, line));
    }
}
