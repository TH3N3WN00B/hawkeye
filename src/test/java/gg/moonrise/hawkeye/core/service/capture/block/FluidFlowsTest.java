package gg.moonrise.hawkeye.core.service.capture.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.type.Stairs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

public class FluidFlowsTest {

    @BeforeEach
    public void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void waterFlowingSidewaysDropsOneLevelAndIsNeverASource() {
        assertEquals(1, sideways(fluid(Material.WATER, 0), false));
        assertEquals(4, sideways(fluid(Material.WATER, 3), false));
    }

    @Test
    public void fallingFluidSpreadsSidewaysLikeASource() {
        assertEquals(1, sideways(fluid(Material.WATER, 8), false));
        assertEquals(1, sideways(fluid(Material.WATER, 12), false));
    }

    @Test
    public void aFluidFlowingDownIsFalling() {
        Levelled down = (Levelled) FluidFlows.flowing(fluid(Material.WATER, 3), true, false).orElseThrow();
        assertEquals(Material.WATER, down.getMaterial());
        assertEquals(FluidFlows.FALLING, down.getLevel());
        assertEquals(FluidFlows.FALLING, ((Levelled) FluidFlows.flowing(fluid(Material.LAVA, 0), true, false)
                .orElseThrow()).getLevel());
    }

    @Test
    public void lavaDropsTwoLevelsExceptWhereItFlowsFast() {
        assertEquals(2, sideways(fluid(Material.LAVA, 0), false));
        assertEquals(6, sideways(fluid(Material.LAVA, 4), false));
        assertEquals(1, sideways(fluid(Material.LAVA, 0), true));
        assertEquals(1, sideways(fluid(Material.WATER, 0), true), "fast lava does not change water");
    }

    @Test
    public void theWeakestFlowStaysFlowing() {
        assertEquals(FluidFlows.WEAKEST, sideways(fluid(Material.LAVA, 6), false));
    }

    @Test
    public void aWaterloggedBlockFlowsAsWater() {
        Stairs stairs = (Stairs) Material.OAK_STAIRS.createBlockData();
        stairs.setWaterlogged(true);
        Levelled flow = (Levelled) FluidFlows.flowing(stairs, false, false).orElseThrow();
        assertEquals(Material.WATER, flow.getMaterial());
        assertEquals(1, flow.getLevel());
    }

    @Test
    public void aBlockWithoutFluidDoesNotFlow() {
        assertTrue(FluidFlows.flowing(Material.DRAGON_EGG.createBlockData(), false, false).isEmpty());
        assertTrue(FluidFlows.flowing(Material.OAK_STAIRS.createBlockData(), false, false).isEmpty());
    }

    private static int sideways(BlockData from, boolean fastLava) {
        Levelled flow = (Levelled) FluidFlows.flowing(from, false, fastLava).orElseThrow();
        assertEquals(from.getMaterial(), flow.getMaterial());
        return flow.getLevel();
    }

    private static Levelled fluid(Material material, int level) {
        Levelled fluid = (Levelled) material.createBlockData();
        fluid.setLevel(level);
        return fluid;
    }
}
