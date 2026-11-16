package gg.moonrise.hawkeye.listener.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.entity.payload.Payload;
import gg.moonrise.hawkeye.listener.Recorded;
import gg.moonrise.hawkeye.support.TestHawkeyePlugin;
import gg.moonrise.hawkeye.support.TestPlugins;
import org.bukkit.GameMode;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

public class GameModeListenerTest {

    private ServerMock server;
    private TestHawkeyePlugin plugin;
    private PlayerMock player;

    @BeforeEach
    public void setUp() {
        server = MockBukkit.mock();
        plugin = TestPlugins.load();
        server.addSimpleWorld("world");
        player = server.addPlayer("Alex");
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void aChangeRecordsTheNewModeAndTheCause() {
        change(GameMode.CREATIVE, PlayerGameModeChangeEvent.Cause.COMMAND);
        change(GameMode.SPECTATOR, PlayerGameModeChangeEvent.Cause.HARDCORE_DEATH);

        StoredEvent first = Recorded.events(Action.GAMEMODE_CHANGE).get(0);
        assertEquals("Alex", first.actor().name());
        assertEquals(new Payload.Text("creative (command)"), first.payload().orElseThrow());
        assertEquals(new Payload.Text("spectator (hardcore_death)"), Recorded.events(Action.GAMEMODE_CHANGE).get(1)
                .payload().orElseThrow());
    }

    @Test
    public void aCancelledChangeOrADisabledCategoryRecordsNothing() {
        PlayerGameModeChangeEvent cancelled = new PlayerGameModeChangeEvent(player, GameMode.CREATIVE,
                PlayerGameModeChangeEvent.Cause.PLUGIN, null);
        cancelled.setCancelled(true);
        server.getPluginManager().callEvent(cancelled);
        TestPlugins.writeConfig(plugin, "logging:\n  gamemodes: false\n");
        TestPlugins.reload();
        change(GameMode.CREATIVE, PlayerGameModeChangeEvent.Cause.COMMAND);

        assertTrue(Recorded.events(Action.GAMEMODE_CHANGE).isEmpty());
    }

    private void change(GameMode mode, PlayerGameModeChangeEvent.Cause cause) {
        server.getPluginManager().callEvent(new PlayerGameModeChangeEvent(player, mode, cause, null));
    }
}
