package gg.moonrise.hawkeye.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.config.message.GeneralMessages;
import gg.moonrise.hawkeye.support.TestCommandManager;
import gg.moonrise.hawkeye.support.TestHawkeyePlugin;
import gg.moonrise.hawkeye.support.TestPlugins;
import gg.moonrise.hawkeye.ui.lookup.AwaitReplies;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.command.ConsoleCommandSenderMock;

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
    public void infoShowsTheVersion() {
        commands.run(console, "hawkeye");
        List<String> replies = TestPlugins.drain(console);
        assertEquals(1, replies.size());
        assertTrue(replies.getFirst().contains(plugin.getPluginMeta().getVersion()), replies.getFirst());
    }

    @Test
    public void theAliasRunsTheSameCommand() {
        commands.run(console, "he help");
        assertEquals(new GeneralMessages().getHelp().size(), TestPlugins.drain(console).size());
    }

    @Test
    public void commandsYmlAddsRootAndSubcommandAliases() {
        TestPlugins.writeFile(plugin, "commands.yml",
                "root-aliases: [he, co]\nsubcommand-aliases:\n  help: [h, commands]\n");
        TestPlugins.reload();
        TestCommandManager aliased = TestPlugins.commands();
        int helpLines = new GeneralMessages().getHelp().size();

        aliased.run(console, "co h");
        assertEquals(helpLines, TestPlugins.drain(console).size());
        aliased.run(console, "he commands");
        assertEquals(helpLines, TestPlugins.drain(console).size());
        aliased.run(console, "hawkeye help");
        assertEquals(helpLines, TestPlugins.drain(console).size());
    }

    @Test
    public void reloadNamesChangedAliasesAsRestartOnly() {
        TestPlugins.writeFile(plugin, "commands.yml", "root-aliases: [co]\n");
        commands.run(console, "hawkeye reload");
        List<String> replies = TestPlugins.drain(console);
        assertEquals(2, replies.size(), replies.toString());
        assertTrue(replies.get(1).contains("commands.yml"), replies.get(1));
    }

    @Test
    public void reloadRefusesAnInvalidAlias() {
        TestPlugins.writeFile(plugin, "commands.yml", "root-aliases: ['core protect']\n");
        commands.run(console, "hawkeye reload");
        List<String> replies = TestPlugins.drain(console);
        assertEquals(1, replies.size(), replies.toString());
        assertTrue(replies.getFirst().contains("commands.yml: root-aliases: 'core protect'"), replies.getFirst());
    }

    @Test
    public void reloadReportsBothOutcomes() {
        commands.run(console, "hawkeye reload");
        assertEquals(List.of("Hawkeye configuration reloaded."), TestPlugins.drain(console));

        TestPlugins.writeConfig(plugin, "server:\n  name: 'Not Valid!'\n");
        commands.run(console, "hawkeye reload");
        List<String> replies = TestPlugins.drain(console);
        assertEquals(1, replies.size());
        assertTrue(replies.getFirst().startsWith("The configuration could not be reloaded"), replies.getFirst());
        assertTrue(replies.getFirst().contains("server.name"), replies.getFirst());
    }

    @Test
    public void reloadNamesRestartOnlySections() {
        TestPlugins.writeConfig(plugin, "server:\n  name: other\n");
        commands.run(console, "hawkeye reload");
        List<String> replies = TestPlugins.drain(console);
        assertEquals(2, replies.size(), replies.toString());
        assertTrue(replies.get(1).contains("server"), replies.get(1));
    }

    @Test
    public void statusShowsTheServerQueueAndArchive() {
        TestPlugins.awaitStorage();
        commands.run(console, "hawkeye status");
        List<String> replies = AwaitReplies.until(server, console,
                received -> received.stream().anyMatch(line -> line.contains("Archive:")));
        assertFalse(replies.isEmpty());
        assertTrue(replies.stream().anyMatch(line -> line.contains("Server: server")), replies.toString());
        assertTrue(replies.stream().anyMatch(line -> line.contains("Queue:")), replies.toString());
        assertTrue(replies.stream().anyMatch(line -> line.contains("Archive: 0 event(s) in 0 segment(s), 0 B")),
                replies.toString());
    }
}
