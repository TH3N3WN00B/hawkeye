package gg.moonrise.hawkeye.core.service.capture.container;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

public class VirtualTransfersTest {

    @BeforeEach
    public void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void itemsTheContainerGainedAndTheViewerLostAreADeposit() {
        List<InventoryDiff.Delta> moves = VirtualTransfers.match(List.of(delta(Material.DIAMOND, 5)),
                List.of(delta(Material.DIAMOND, -5)));
        assertEquals(List.of(5), amounts(moves));
        assertEquals(Material.DIAMOND, moves.getFirst().item().getType());
    }

    @Test
    public void itemsTheContainerLostAndTheViewerGainedAreAWithdrawal() {
        assertEquals(List.of(-3), amounts(VirtualTransfers.match(List.of(delta(Material.EMERALD, -3)),
                List.of(delta(Material.EMERALD, 3)))));
    }

    @Test
    public void onlyTheSmallerCountMoved() {
        assertEquals(List.of(4), amounts(VirtualTransfers.match(List.of(delta(Material.COAL, 10)),
                List.of(delta(Material.COAL, -4)))));
        assertEquals(List.of(-2), amounts(VirtualTransfers.match(List.of(delta(Material.COAL, -2)),
                List.of(delta(Material.COAL, 7)))));
    }

    @Test
    public void aMenuChangingItsOwnPageIsNothing() {
        List<InventoryDiff.Delta> pageFlip = List.of(delta(Material.DIAMOND, -1), delta(Material.EMERALD, 1),
                delta(Material.BARRIER, 1));
        assertTrue(VirtualTransfers.match(pageFlip, List.of()).isEmpty());
    }

    @Test
    public void aShopGivingAnItemIsNothing() {
        assertTrue(VirtualTransfers.match(List.of(), List.of(delta(Material.DIAMOND_SWORD, 1))).isEmpty());
    }

    @Test
    public void changesInTheSameDirectionAreNothing() {
        assertTrue(VirtualTransfers.match(List.of(delta(Material.STONE, 5)), List.of(delta(Material.STONE, 5)))
                .isEmpty());
        assertTrue(VirtualTransfers.match(List.of(delta(Material.STONE, -5)), List.of(delta(Material.STONE, -5)))
                .isEmpty());
    }

    @Test
    public void twoKindsAreMatchedApartAndOnlyIdenticalItemsMatch() {
        ItemStack named = new ItemStack(Material.DIAMOND);
        named.editMeta(meta -> meta.displayName(Component.text("Prize")));
        List<InventoryDiff.Delta> moves = VirtualTransfers.match(
                List.of(delta(Material.DIAMOND, 2), new InventoryDiff.Delta(named, 1), delta(Material.GOLD_INGOT, -6)),
                List.of(new InventoryDiff.Delta(named, -1), delta(Material.GOLD_INGOT, 6),
                        delta(Material.IRON_INGOT, -2)));
        assertEquals(List.of(1, -6), amounts(moves));
        assertTrue(moves.getFirst().item().isSimilar(named));
        assertEquals(Material.GOLD_INGOT, moves.get(1).item().getType());
    }

    @Test
    public void accountedMovesAreTakenOutOfTheChanges() {
        List<InventoryDiff.Delta> changes = List.of(delta(Material.DIAMOND, 2), delta(Material.STONE, 4));
        List<InventoryDiff.Delta> left = VirtualTransfers.without(changes,
                List.of(delta(Material.DIAMOND, 5), delta(Material.STONE, 4), delta(Material.COAL, 1)));
        assertEquals(List.of(-3, -1), amounts(left));
        assertEquals(Material.DIAMOND, left.getFirst().item().getType());
        assertEquals(Material.COAL, left.get(1).item().getType());
        assertEquals(changes, VirtualTransfers.without(changes, List.of()));
    }

    private static InventoryDiff.Delta delta(Material material, int amount) {
        return new InventoryDiff.Delta(new ItemStack(material), amount);
    }

    private static List<Integer> amounts(List<InventoryDiff.Delta> deltas) {
        return deltas.stream().map(InventoryDiff.Delta::amount).toList();
    }
}
