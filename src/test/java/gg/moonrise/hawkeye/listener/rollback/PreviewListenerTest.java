package gg.moonrise.hawkeye.listener.rollback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.command.RollbackTestSupport;
import gg.moonrise.hawkeye.config.permission.Permissions;
import gg.moonrise.hawkeye.core.service.capture.Actors;
import gg.moonrise.hawkeye.core.service.capture.EventRecorder;
import gg.moonrise.hawkeye.core.service.rollback.RollbackPreviews;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.support.TestCommandManager;
import gg.moonrise.hawkeye.support.TestHawkeyePlugin;
import gg.moonrise.hawkeye.support.TestPlugins;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

public class PreviewListenerTest {

    private ServerMock server;
    private TestHawkeyePlugin plugin;
    private World world;
    private PlayerMock alex;
    private TestCommandManager commands;

    @BeforeEach
    public void setUp() {
        server = MockBukkit.mock();
        plugin = TestPlugins.load();
        TestPlugins.awaitStorage();
        world = server.addSimpleWorld("world");
        alex = server.addPlayer("Alex");
        alex.teleport(new Location(world, 0.5, 64, 0.5));
        alex.addAttachment(plugin, Permissions.ROLLBACK, true);
        commands = RollbackTestSupport.commands();
        TestPlugins.drain(alex);
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void leavingEndsThePreview() {
        TestPlugins.bean(EventRecorder.class).record(Action.BLOCK_BREAK, Actors.of(alex), world, 3, 64, 2,
                "minecraft:stone", "minecraft:air", 0, null);
        Block block = world.getBlockAt(3, 64, 2);
        block.setType(Material.AIR);
        TestPlugins.flush();
        commands.run(alex, "hawkeye rollback u:alex t:1h r:10 #preview");
        RollbackTestSupport.tickUntilReply(server, alex, "Previewing 1 block(s)");
        RollbackPreviews previews = TestPlugins.bean(RollbackPreviews.class);
        assertTrue(previews.has(alex.getUniqueId()));

        alex.disconnect();
        assertFalse(previews.has(alex.getUniqueId()));

        // Back after a relog: the client shows the real blocks, so there is nothing to apply.
        alex.reconnect();
        alex.addAttachment(plugin, Permissions.ROLLBACK, true);
        TestPlugins.drain(alex);
        commands.run(alex, "hawkeye apply");
        RollbackTestSupport.tickUntilReply(server, alex, "You have no preview to apply or cancel.");
        assertEquals(Material.AIR, block.getType());
    }
}
