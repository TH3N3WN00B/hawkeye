package gg.moonrise.hawkeye.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.support.TestCommandManager;
import gg.moonrise.hawkeye.support.TestCommandSource;
import gg.moonrise.hawkeye.support.TestHawkeyePlugin;
import gg.moonrise.hawkeye.support.TestPlugins;
import java.util.List;
import org.incendo.cloud.suggestion.Suggestion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.command.ConsoleCommandSenderMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

public class HawkeyeCommandTest {

    private ServerMock server;
    private TestHawkeyePlugin plugin;
    private TestCommandManager commands;
    private ConsoleCommandSenderMock console;

    @BeforeEach
    public void setUp() {
        server = MockBukkit.mock();
        plugin = TestPlugins.load();
        commands = TestPlugins.commands();
        console = server.getConsoleSender();
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void infoShowsNameAndVersion() {
        commands.run(console, HawkeyeCommand.ROOT);
        assertEquals(List.of(plugin.getName() + " " + plugin.getPluginMeta().getVersion()), TestPlugins.drain(console));
    }

    @Test
    public void reloadReportsBothOutcomes() {
        commands.run(console, HawkeyeCommand.ROOT + " reload");
        assertEquals(List.of("Configuration reloaded."), TestPlugins.drain(console));

        TestPlugins.writeConfig(plugin, "greetings-enabled: [broken]\n");
        commands.run(console, HawkeyeCommand.ROOT + " reload");
        List<String> replies = TestPlugins.drain(console);
        assertEquals(1, replies.size());
        assertTrue(replies.getFirst().startsWith("The configuration could not be reloaded"), replies.getFirst());
    }

    @Test
    public void greetSendsTheGreetingToTheTarget() {
        PlayerMock target = server.addPlayer("Alex");
        TestPlugins.drain(target);
        commands.run(console, HawkeyeCommand.ROOT + " greet Alex");
        assertEquals(List.of("Welcome back, Alex."), TestPlugins.drain(target));
        assertEquals(List.of("Greeted Alex."), TestPlugins.drain(console));
    }

    @Test
    public void greetNamesAMissingPlayer() {
        commands.run(console, HawkeyeCommand.ROOT + " greet Nobody");
        assertEquals(List.of("No online player is called Nobody."), TestPlugins.drain(console));
    }

    @Test
    public void suggestsOnlinePlayersContainingTheInput() {
        server.addPlayer("Alex");
        server.addPlayer("Steve");
        server.addPlayer("alexandra");
        List<String> suggestions = commands.suggestionFactory()
                .suggestImmediately(new TestCommandSource(console), HawkeyeCommand.ROOT + " greet LEX")
                .list().stream().map(Suggestion::suggestion).toList();
        assertEquals(List.of("Alex", "alexandra"), suggestions);
    }
}
