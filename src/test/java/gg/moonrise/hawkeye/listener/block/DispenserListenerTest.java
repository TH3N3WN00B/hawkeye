package gg.moonrise.hawkeye.listener.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.core.service.capture.CauseTracker;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.listener.Recorded;
import gg.moonrise.hawkeye.support.TestPlugins;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

public class DispenserListenerTest {

    private ServerMock server;
    private World world;
    private Block dispenser;
    private Block front;

    @BeforeEach
    public void setUp() {
        server = MockBukkit.mock();
        TestPlugins.load();
        world = server.addSimpleWorld("world");
        dispenser = world.getBlockAt(0, 64, 0);
        Directional facing = (Directional) Material.DISPENSER.createBlockData();
        facing.setFacing(BlockFace.EAST);
        dispenser.setBlockData(facing);
        front = world.getBlockAt(1, 64, 0);
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void aDispensedWaterBucketIsAPlaceByTheDispenserAndItsFlowIsRemembered() {
        dispense(dispenser, Material.WATER_BUCKET);
        front.setType(Material.WATER);
        server.getScheduler().performOneTick();

        StoredEvent event = Recorded.only(Action.BLOCK_PLACE);
        assertEquals("#dispenser", event.actor().name());
        assertEquals(1, event.x());
        assertEquals("minecraft:air", event.secondary());
        assertEquals("#dispenser", TestPlugins.bean(CauseTracker.class).fluidCause(front).orElseThrow().name());
    }

    @Test
    public void anEmptyBucketPickingUpWaterIsABreak() {
        front.setType(Material.WATER);
        dispense(dispenser, Material.BUCKET);
        front.setType(Material.AIR);
        server.getScheduler().performOneTick();

        StoredEvent event = Recorded.only(Action.BLOCK_BREAK);
        assertEquals("#dispenser", event.actor().name());
        assertTrue(event.subject().startsWith("minecraft:water"), event.subject());
    }

    @Test
    public void nothingChangedOrAChangeAlreadyRecordedIsNotRecordedAgain() {
        dispense(dispenser, Material.ARROW);
        server.getScheduler().performOneTick();
        dispense(dispenser, Material.FLINT_AND_STEEL);
        server.getPluginManager().callEvent(new BlockIgniteEvent(front, BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL,
                dispenser));
        front.setType(Material.FIRE);
        server.getScheduler().performOneTick();

        StoredEvent fire = Recorded.only(Action.BLOCK_PLACE);
        assertEquals("#dispenser", fire.actor().name(), "the fire listener's own record");
        assertTrue(Recorded.events(Action.BLOCK_BREAK, Action.BLOCK_CHANGE).isEmpty());
    }

    @Test
    public void aDropperIsSkipped() {
        Block dropper = world.getBlockAt(0, 70, 0);
        Directional facing = (Directional) Material.DROPPER.createBlockData();
        facing.setFacing(BlockFace.EAST);
        dropper.setBlockData(facing);
        dispense(dropper, Material.WATER_BUCKET);
        dropper.getRelative(BlockFace.EAST).setType(Material.WATER);
        server.getScheduler().performOneTick();

        assertTrue(Recorded.events().isEmpty());
    }

    private void dispense(Block from, Material item) {
        server.getPluginManager().callEvent(new BlockDispenseEvent(from, new ItemStack(item), new Vector()));
    }
}
