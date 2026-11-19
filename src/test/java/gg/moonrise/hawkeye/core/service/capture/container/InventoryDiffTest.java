package gg.moonrise.hawkeye.core.service.capture.container;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

public class InventoryDiffTest {

    @BeforeEach
    public void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void anAddedStackIsADepositAndARemovedOneAWithdrawal() {
        ItemStack[] before = {new ItemStack(Material.STONE, 10), null};
        ItemStack[] after = {null, new ItemStack(Material.DIAMOND, 3)};
        List<InventoryDiff.Delta> deltas = InventoryDiff.diff(before, after);
        assertEquals(2, deltas.size());
        assertEquals(Material.STONE, deltas.get(0).item().getType());
        assertEquals(-10, deltas.get(0).amount());
        assertEquals(Material.DIAMOND, deltas.get(1).item().getType());
        assertEquals(3, deltas.get(1).amount());
    }

    @Test
    public void movingOrSplittingAStackChangesNothing() {
        ItemStack[] before = {new ItemStack(Material.STONE, 64), null, null};
        ItemStack[] after = {null, new ItemStack(Material.STONE, 32), new ItemStack(Material.STONE, 32)};
        assertTrue(InventoryDiff.diff(before, after).isEmpty());
    }

    @Test
    public void onlyTheNetCountOfSimilarItemsIsReported() {
        ItemStack[] before = {new ItemStack(Material.STONE, 5), new ItemStack(Material.STONE, 5)};
        ItemStack[] after = {new ItemStack(Material.STONE, 64), new ItemStack(Material.STONE, 1)};
        List<InventoryDiff.Delta> deltas = InventoryDiff.diff(before, after);
        assertEquals(1, deltas.size());
        assertEquals(55, deltas.getFirst().amount());
    }

    @Test
    public void itemsThatDifferInMetaAreDifferentKinds() {
        ItemStack named = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta meta = named.getItemMeta();
        meta.displayName(Component.text("Excalibur"));
        named.setItemMeta(meta);
        ItemStack[] before = {new ItemStack(Material.DIAMOND_SWORD)};
        ItemStack[] after = {named};
        List<InventoryDiff.Delta> deltas = InventoryDiff.diff(before, after);
        assertEquals(2, deltas.size());
        assertEquals(-1, deltas.get(0).amount());
        assertEquals(1, deltas.get(1).amount());
        assertTrue(deltas.get(1).item().hasItemMeta());
    }

    @Test
    public void aCopyIsDetachedFromTheLiveStacks() {
        ItemStack live = new ItemStack(Material.STONE, 5);
        ItemStack[] copy = InventoryDiff.copy(new ItemStack[] {live, new ItemStack(Material.AIR), null});
        live.setAmount(1);
        assertNotSame(live, copy[0]);
        assertEquals(5, copy[0].getAmount());
        assertNull(copy[1]);
        assertNull(copy[2]);
    }
}
