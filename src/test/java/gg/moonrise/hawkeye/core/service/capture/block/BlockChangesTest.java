package gg.moonrise.hawkeye.core.service.capture.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.core.service.capture.EventRecorder;
import gg.moonrise.hawkeye.entity.action.Action;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Stairs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

public class BlockChangesTest {

    @BeforeEach
    public void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void aChangeToAirIsABreakOfTheOldState() {
        BlockData dirt = Material.DIRT.createBlockData();
        BlockData air = Material.AIR.createBlockData();
        BlockChange change = BlockChanges.classify(dirt, air).orElseThrow();
        assertEquals(Action.BLOCK_BREAK, change.action());
        assertSame(dirt, change.subject());
        assertSame(air, change.secondary());
    }

    @Test
    public void aChangeFromAirOrAReplaceableIsAPlaceOfTheNewState() {
        BlockData sand = Material.SAND.createBlockData();
        for (Material empty : new Material[] {Material.AIR, Material.WATER, Material.LAVA, Material.SHORT_GRASS,
                Material.SNOW}) {
            BlockData before = empty.createBlockData();
            BlockChange change = BlockChanges.classify(before, sand).orElseThrow();
            assertEquals(Action.BLOCK_PLACE, change.action(), empty.name());
            assertSame(sand, change.subject());
            assertSame(before, change.secondary());
        }
    }

    @Test
    public void anyOtherChangeIsAChangeInPlace() {
        BlockData grass = Material.GRASS_BLOCK.createBlockData();
        BlockData dirt = Material.DIRT.createBlockData();
        BlockChange change = BlockChanges.classify(grass, dirt).orElseThrow();
        assertEquals(Action.BLOCK_CHANGE, change.action());
        assertSame(dirt, change.subject());
        assertSame(grass, change.secondary());
    }

    @Test
    public void aWaterloggedOrUnderwaterBlockThatBecomesWaterIsABreak() {
        Stairs stairs = (Stairs) Material.OAK_STAIRS.createBlockData();
        stairs.setWaterlogged(true);
        BlockData water = Material.WATER.createBlockData();
        assertEquals(Action.BLOCK_BREAK, BlockChanges.classify(stairs, water).orElseThrow().action());
        assertEquals(Action.BLOCK_BREAK, BlockChanges.classify(Material.SEAGRASS.createBlockData(), water)
                .orElseThrow().action());
        assertEquals(Action.BLOCK_PLACE, BlockChanges.classify(Material.SHORT_GRASS.createBlockData(), water)
                .orElseThrow().action(), "a dry plant flooded is water placed");
    }

    @Test
    public void anUnchangedStateOrAirToAirIsNothing() {
        assertTrue(BlockChanges.classify(Material.STONE.createBlockData(), Material.STONE.createBlockData()).isEmpty());
        assertTrue(BlockChanges.classify(Material.CAVE_AIR.createBlockData(), Material.AIR.createBlockData())
                .isEmpty());
    }

    @Test
    public void aWaterloggedOrUnderwaterBlockLeavesWater() {
        Stairs stairs = (Stairs) Material.OAK_STAIRS.createBlockData();
        assertEquals(EventRecorder.AIR, BlockChanges.remains(stairs));
        stairs.setWaterlogged(true);
        assertEquals(BlockChanges.WATER, BlockChanges.remains(stairs));
        assertEquals(BlockChanges.WATER, BlockChanges.remains(Material.KELP_PLANT.createBlockData()));
        assertEquals(EventRecorder.AIR, BlockChanges.remains(Material.STONE.createBlockData()));
    }

    @Test
    public void fluidFormsAreWaterAndLavaMakingStone() {
        assertTrue(BlockChanges.isFluidForm(Material.LAVA, Material.OBSIDIAN));
        assertTrue(BlockChanges.isFluidForm(Material.LAVA, Material.COBBLESTONE));
        assertTrue(BlockChanges.isFluidForm(Material.WATER, Material.STONE));
        assertFalse(BlockChanges.isFluidForm(Material.WATER, Material.ICE));
        assertFalse(BlockChanges.isFluidForm(Material.AIR, Material.SNOW));
    }
}
