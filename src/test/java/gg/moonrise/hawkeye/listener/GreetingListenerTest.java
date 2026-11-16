package gg.moonrise.hawkeye.listener;

import static org.junit.jupiter.api.Assertions.assertEquals;

import gg.moonrise.hawkeye.config.HawkeyeConfigProvider;
import gg.moonrise.hawkeye.support.TestHawkeyePlugin;
import gg.moonrise.hawkeye.support.TestPlugins;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

public class GreetingListenerTest {

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
    public void greetsAPlayerOnTheirFirstJoin() {
        PlayerMock player = server.addPlayer("Alex");
        assertEquals(List.of("Welcome to the server, Alex!"), TestPlugins.drain(player));
    }

    @Test
    public void staysSilentWhenGreetingsAreOff() {
        TestPlugins.writeConfig(plugin, "greetings-enabled: false\n");
        TestPlugins.bean(HawkeyeConfigProvider.class).reloadConfig();
        PlayerMock player = server.addPlayer("Alex");
        assertEquals(List.of(), TestPlugins.drain(player));
    }
}
