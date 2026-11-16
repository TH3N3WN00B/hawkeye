package gg.moonrise.hawkeye.listener.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.entity.payload.Payload;
import gg.moonrise.hawkeye.listener.Recorded;
import gg.moonrise.hawkeye.support.TestPlugins;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.event.player.PlayerKickEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

public class SessionListenerTest {

    private ServerMock server;

    @BeforeEach
    public void setUp() {
        server = MockBukkit.mock();
        TestPlugins.load();
        server.addSimpleWorld("world");
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void aJoinAndAQuitAreRecorded() {
        PlayerMock player = server.addPlayer("Alex");
        player.disconnect();

        List<StoredEvent> events = Recorded.events(Action.JOIN, Action.QUIT);
        assertEquals(List.of(Action.JOIN, Action.QUIT), events.stream().map(StoredEvent::action).toList());
        assertEquals("Alex", events.getFirst().actor().name());
        assertTrue(events.get(1).payload().isEmpty());
    }

    @Test
    public void aKickedPlayersQuitCarriesTheReasonOnOneLine() {
        PlayerMock player = server.addPlayer("Alex");
        // Paper fires the kick, then the quit; MockBukkit's PlayerMock#kick fires no quit, so both are sent here.
        server.getPluginManager().callEvent(new PlayerKickEvent(player, Component.text("Flying is not enabled",
                NamedTextColor.RED).append(Component.newline()).append(Component.text("on this server")),
                Component.text("Alex left"), PlayerKickEvent.Cause.FLYING_PLAYER));
        assertEquals(1, TestPlugins.bean(SessionListener.class).pendingKicks());
        player.disconnect();

        StoredEvent quit = Recorded.only(Action.QUIT);
        assertEquals("Alex", quit.actor().name());
        assertEquals(new Payload.Text("kicked: Flying is not enabled on this server"), quit.payload().orElseThrow());
        assertEquals(0, TestPlugins.bean(SessionListener.class).pendingKicks());
    }

    @Test
    public void aCancelledKickLeavesTheNextQuitPlain() {
        PlayerMock player = server.addPlayer("Alex");
        PlayerKickEvent kick = new PlayerKickEvent(player, Component.text("AFK"), Component.text("Alex left"),
                PlayerKickEvent.Cause.IDLING);
        kick.setCancelled(true);
        server.getPluginManager().callEvent(kick);
        player.disconnect();

        assertTrue(Recorded.only(Action.QUIT).payload().isEmpty());
    }
}
