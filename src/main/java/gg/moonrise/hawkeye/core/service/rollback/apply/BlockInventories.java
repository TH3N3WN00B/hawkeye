package gg.moonrise.hawkeye.core.service.rollback.apply;

import gg.moonrise.hawkeye.entity.payload.Payload;
import gg.moonrise.hawkeye.util.serialize.ItemBytes;
import io.papermc.paper.block.TileStateInventoryHolder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.Jukebox;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * The items of a block that holds some: every container (chests, barrels, furnaces, hoppers, ...) and the
 * blocks that hold items without being containers, through their inventory (lecterns with their one book
 * slot, chiseled bookshelves, decorated pots, shelves). A jukebox's one record goes through its record
 * API, which keeps the block's has-record state; on Paper applying it starts the disc again. Paper's
 * block states are snapshots whose {@code getInventory()} is the block's live inventory, so inventory
 * changes need no update. Call only on the thread that owns the block.
 */
@Slf4j
public final class BlockInventories {

    private BlockInventories() {
    }

    /**
     * Puts items into ({@code delta > 0}) or takes them out of ({@code delta < 0}) a block's inventory.
     *
     * @param block    the block
     * @param template the item, any amount
     * @param delta    how many
     * @return how many items moved, or empty when the block holds no items
     */
    public static @NotNull OptionalInt adjust(@NotNull Block block, @NotNull ItemStack template, int delta) {
        BlockState state = block.getState();
        if (state instanceof Jukebox jukebox) {
            return OptionalInt.of(adjustRecord(jukebox, template, delta));
        }
        if (!(state instanceof TileStateInventoryHolder holder)) {
            return OptionalInt.empty();
        }
        int requested = Math.abs(delta);
        int perStack = Math.max(1, template.getMaxStackSize());
        List<ItemStack> stacks = new ArrayList<>();
        for (int left = requested; left > 0; left -= perStack) {
            stacks.add(template.asQuantity(Math.min(perStack, left)));
        }
        Inventory inventory = holder.getInventory();
        ItemStack[] moved = stacks.toArray(ItemStack[]::new);
        Map<Integer, ItemStack> missed = delta > 0 ? inventory.addItem(moved) : inventory.removeItem(moved);
        return OptionalInt.of(requested - missed.values().stream().mapToInt(ItemStack::getAmount).sum());
    }

    /**
     * Replaces a block's items with recorded slots (a broken container a rollback puts back). A block
     * that holds no items is left alone.
     *
     * @param block    the block, already set to its recorded state
     * @param contents the non-empty slots
     */
    public static void fill(@NotNull Block block, @NotNull List<Payload.Slot> contents) {
        BlockState state = block.getState();
        if (state instanceof Jukebox jukebox) {
            ItemStack record = contents.stream().filter(slot -> slot.index() == 0).findFirst()
                    .map(slot -> item(block, slot)).orElse(ItemStack.empty());
            setRecord(jukebox, record);
            return;
        }
        if (!(state instanceof TileStateInventoryHolder holder)) {
            return;
        }
        // One half of a double chest holds its own 27 slots, which is what was recorded.
        Inventory inventory = holder instanceof Chest chest ? chest.getBlockInventory() : holder.getInventory();
        inventory.clear();
        for (Payload.Slot slot : contents) {
            if (slot.index() >= 0 && slot.index() < inventory.getSize()) {
                inventory.setItem(slot.index(), item(block, slot));
            }
        }
    }

    private static int adjustRecord(@NotNull Jukebox jukebox, @NotNull ItemStack template, int delta) {
        ItemStack record = jukebox.getRecord();
        if (delta > 0 && record.isEmpty()) {
            setRecord(jukebox, template.asOne());
            return 1;
        }
        if (delta < 0 && record.isSimilar(template)) {
            setRecord(jukebox, ItemStack.empty());
            return 1;
        }
        return 0;
    }

    private static void setRecord(@NotNull Jukebox jukebox, @NotNull ItemStack record) {
        jukebox.setRecord(record);
        jukebox.update(true, false);
    }

    private static @NotNull ItemStack item(@NotNull Block block, @NotNull Payload.Slot slot) {
        try {
            return ItemBytes.deserialize(slot.item());
        } catch (RuntimeException e) {
            log.debug("Could not restore slot {} at {}", slot.index(), block.getLocation(), e);
            return ItemStack.empty();
        }
    }
}
