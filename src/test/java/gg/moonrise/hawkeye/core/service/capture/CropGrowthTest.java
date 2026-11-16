package gg.moonrise.hawkeye.core.service.capture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Material;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Hatchable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

public class CropGrowthTest {

    @BeforeEach
    public void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void aRipeCropIsRecordedAndAnIntermediateStageIsNot() {
        assertTrue(CropGrowth.recorded(wheat(6), wheat(7), false));
        assertFalse(CropGrowth.recorded(wheat(2), wheat(3), false));
    }

    @Test
    public void aNewBlockIsRecorded() {
        BlockData air = Material.AIR.createBlockData();
        assertTrue(CropGrowth.recorded(air, Material.MELON.createBlockData(), false));
        assertTrue(CropGrowth.recorded(air, Material.SUGAR_CANE.createBlockData(), false));
        assertTrue(CropGrowth.recorded(Material.SMALL_AMETHYST_BUD.createBlockData(),
                Material.MEDIUM_AMETHYST_BUD.createBlockData(), false));
    }

    @Test
    public void everyStageIsRecordedWhenStagesAreOn() {
        assertTrue(CropGrowth.recorded(wheat(2), wheat(3), true));
    }

    @Test
    public void anUnchangedStateIsNeverRecorded() {
        assertFalse(CropGrowth.recorded(wheat(7), wheat(7), true));
    }

    @Test
    public void aStageThatIsNotAnAgeIsAnIntermediateStage() {
        Hatchable egg = (Hatchable) Material.SNIFFER_EGG.createBlockData();
        Hatchable cracked = (Hatchable) egg.clone();
        cracked.setHatch(1);
        assertFalse(CropGrowth.recorded(egg, cracked, false));
    }

    private static Ageable wheat(int age) {
        Ageable wheat = (Ageable) Material.WHEAT.createBlockData();
        wheat.setAge(age);
        return wheat;
    }
}
