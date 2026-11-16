package gg.moonrise.hawkeye.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.core.service.inspect.InspectorService;
import gg.moonrise.hawkeye.support.TestCommandManager;
import gg.moonrise.hawkeye.support.TestPlugins;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

public class InspectCommandTest {

    private ServerMock server;
    private TestCommandManager commands;
    private PlayerMock alex;

    @BeforeEach
    public void setUp() {
        server = MockBukkit.mock();
        TestPlugins.load();
        commands = TestPlugins.commands();
        server.addSimpleWorld("world");
        alex = server.addPlayer("Alex");
        alex.setOp(true);
        TestPlugins.drain(alex);
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void inspectTogglesTheInspector() {
        InspectorService inspector = TestPlugins.bean(InspectorService.class);

        commands.run(alex, "hawkeye inspect");
        List<String> on = TestPlugins.drain(alex);
        assertEquals(1, on.size());
        assertTrue(on.getFirst().startsWith("Inspector on:"), on.getFirst());
        assertTrue(inspector.isInspecting(alex.getUniqueId()));

        commands.run(alex, "he i");
        assertEquals(List.of("Inspector off."), TestPlugins.drain(alex));
        assertFalse(inspector.isInspecting(alex.getUniqueId()));
    }

    @Test
    public void theInspectorIsForPlayers() {
        commands.run(server.getConsoleSender(), "hawkeye inspect");
        assertEquals(List.of("Only players can do that."), TestPlugins.drain(server.getConsoleSender()));
    }
}
