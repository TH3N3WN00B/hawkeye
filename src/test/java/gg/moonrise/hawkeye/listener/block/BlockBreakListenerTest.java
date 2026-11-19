package gg.moonrise.hawkeye.listener.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.core.service.capture.EventRecorder;
import gg.moonrise.hawkeye.core.service.capture.block.BlockChanges;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.entity.payload.Payload;
import gg.moonrise.hawkeye.entity.payload.SignSides;
import gg.moonrise.hawkeye.listener.Recorded;
import gg.moonrise.hawkeye.support.TestHawkeyePlugin;
import gg.moonrise.hawkeye.support.TestPlugins;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.Sign;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.block.sign.Side;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

public class BlockBreakListenerTest {

    private ServerMock server;
    private TestHawkeyePlugin plugin;
    private World world;
    private PlayerMock player;

    @BeforeEach
    public void setUp() {
        server = MockBukkit.mock();
        plugin = TestPlugins.load();
        world = server.addSimpleWorld("world");
        player = server.addPlayer("Alex");
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void aBrokenBlockIsRecordedWithWhatRemains() {
        Block block = world.getBlockAt(4, 64, -2);
        block.setType(Material.STONE);
        breakBlock(block);

        StoredEvent event = Recorded.only(Action.BLOCK_BREAK);
        assertEquals("Alex", event.actor().name());
        assertEquals("minecraft:stone", event.subject());
        assertEquals(EventRecorder.AIR, event.secondary());
        assertEquals(4, event.x());
        assertEquals(64, event.y());
        assertEquals(-2, event.z());
        assertTrue(event.payload().isEmpty());
    }

    @Test
    public void aWaterloggedBlockLeavesWater() {
        Block block = world.getBlockAt(0, 64, 0);
        Stairs stairs = (Stairs) Material.OAK_STAIRS.createBlockData();
        stairs.setWaterlogged(true);
        block.setBlockData(stairs);
        breakBlock(block);

        StoredEvent event = Recorded.only(Action.BLOCK_BREAK);
        assertEquals(stairs.getAsString(), event.subject());
        assertEquals(BlockChanges.WATER, event.secondary());
    }

    @Test
    public void aContainerKeepsItsItems() {
        Block block = world.getBlockAt(0, 64, 0);
        block.setType(Material.CHEST);
        ((Chest) block.getState()).getInventory().setItem(5, new ItemStack(Material.DIAMOND, 7));
        breakBlock(block);

        Payload.Inventory inventory = (Payload.Inventory) Recorded.only(Action.BLOCK_BREAK).payload().orElseThrow();
        assertEquals(1, inventory.slots().size());
        assertEquals(5, inventory.slots().getFirst().index());
    }

    @Test
    public void aSignKeepsItsText() {
        Block block = world.getBlockAt(0, 64, 0);
        block.setType(Material.OAK_SIGN);
        ((Sign) block.getState()).getSide(Side.BACK).line(0, Component.text("Hello"));
        breakBlock(block);

        Payload.Sign sign = (Payload.Sign) Recorded.only(Action.BLOCK_BREAK).payload().orElseThrow();
        assertEquals(List.of(), sign.before().front());
        assertEquals(4, sign.before().back().size());
        assertEquals(SignSides.EMPTY, sign.after());
    }

    @Test
    public void bothHalvesOfADoorAreRecorded() {
        Block bottom = world.getBlockAt(0, 64, 0);
        Door lower = (Door) Material.OAK_DOOR.createBlockData();
        bottom.setBlockData(lower);
        Door upper = (Door) Material.OAK_DOOR.createBlockData();
        upper.setHalf(Bisected.Half.TOP);
        world.getBlockAt(0, 65, 0).setBlockData(upper);
        breakBlock(bottom);

        List<StoredEvent> breaks = Recorded.events(Action.BLOCK_BREAK);
        assertEquals(2, breaks.size());
        assertEquals(64, breaks.get(0).y());
        assertEquals(lower.getAsString(), breaks.get(0).subject());
        assertEquals(65, breaks.get(1).y());
        assertEquals(upper.getAsString(), breaks.get(1).subject());
    }

    @Test
    public void aDisabledCategoryRecordsNothing() {
        TestPlugins.writeConfig(plugin, "logging:\n  block-break: false\n");
        TestPlugins.reload();
        Block block = world.getBlockAt(0, 64, 0);
        block.setType(Material.STONE);
        breakBlock(block);

        assertTrue(Recorded.events(Action.BLOCK_BREAK).isEmpty());
    }

    @Test
    public void aCancelledBreakRecordsNothing() {
        Block block = world.getBlockAt(0, 64, 0);
        block.setType(Material.STONE);
        BlockBreakEvent event = new BlockBreakEvent(block, player);
        event.setCancelled(true);
        server.getPluginManager().callEvent(event);

        assertTrue(Recorded.events(Action.BLOCK_BREAK).isEmpty());
    }

    private void breakBlock(Block block) {
        server.getPluginManager().callEvent(new BlockBreakEvent(block, player));
        block.setType(Material.AIR);
    }
}
