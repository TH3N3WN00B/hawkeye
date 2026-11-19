package gg.moonrise.hawkeye.core.service.capture.container;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.inventory.HopperInventoryMock;

public class InventorySpaceTest {

    private Inventory hopper;

    @BeforeEach
    public void setUp() {
        MockBukkit.mock();
        hopper = new HopperInventoryMock(null);
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void anEmptyInventoryTakesTheWholeStack() {
        assertEquals(64, InventorySpace.room(hopper, new ItemStack(Material.COBBLESTONE, 64)));
    }

    @Test
    public void partlyFilledSlotsOfTheSameItemCount() {
        for (int slot = 0; slot < hopper.getSize(); slot++) {
            hopper.setItem(slot, new ItemStack(Material.DIRT, 64));
        }
        hopper.setItem(1, new ItemStack(Material.COBBLESTONE, 60));
        hopper.setItem(3, new ItemStack(Material.COBBLESTONE, 63));

        assertEquals(5, InventorySpace.room(hopper, new ItemStack(Material.COBBLESTONE, 20)));
    }

    @Test
    public void aFullInventoryOrADifferentItemTakesNothing() {
        for (int slot = 0; slot < hopper.getSize(); slot++) {
            hopper.setItem(slot, new ItemStack(Material.DIRT, 10));
        }

        assertEquals(0, InventorySpace.room(hopper, new ItemStack(Material.COBBLESTONE, 3)));
        assertEquals(3, InventorySpace.room(hopper, new ItemStack(Material.DIRT, 3)));
    }

    @Test
    public void unstackableItemsTakeOneSlotEach() {
        for (int slot = 2; slot < hopper.getSize(); slot++) {
            hopper.setItem(slot, new ItemStack(Material.DIAMOND_SWORD));
        }

        assertEquals(2, InventorySpace.room(hopper, new ItemStack(Material.DIAMOND_SWORD, 3)));
    }
}
