package gg.moonrise.hawkeye.core.service.capture.container;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * How much of an item an inventory can still take, for events that fire before the server moves the
 * item and may move only part of it, or none (a hopper picking up a ground item).
 */
public final class InventorySpace {

    private InventorySpace() {
    }

    /**
     * How many of an item fit: empty slots and slots holding a similar item that is not yet a full
     * stack, counted up to the item's amount.
     *
     * @param inventory the inventory
     * @param item      the item
     * @return how many of it fit, from 0 to its amount
     */
    public static int room(@NotNull Inventory inventory, @NotNull ItemStack item) {
        if (item.isEmpty()) {
            return 0;
        }
        int wanted = item.getAmount();
        int perSlot = Math.max(1, Math.min(item.getMaxStackSize(), inventory.getMaxStackSize()));
        int room = 0;
        for (ItemStack slot : inventory.getStorageContents()) {
            if (slot == null || slot.isEmpty()) {
                room += perSlot;
            } else if (slot.isSimilar(item)) {
                room += Math.max(0, perSlot - slot.getAmount());
            }
            if (room >= wanted) {
                return wanted;
            }
        }
        return room;
    }
}
