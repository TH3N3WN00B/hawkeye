package gg.moonrise.hawkeye.core.service.capture.block;

import gg.moonrise.hawkeye.core.service.capture.text.SignText;
import gg.moonrise.hawkeye.entity.payload.Payload;
import gg.moonrise.hawkeye.entity.payload.SignSides;
import gg.moonrise.hawkeye.util.ItemBytes;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.Sign;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * Reads what a block holds beyond its state, for a rollback of its removal to put back: the items of
 * a container (only this block's half of a double chest) or the text of a sign. Reads the live block
 * entity without copying it, so call it on the block's own thread before the block is removed.
 */
public final class BlockContents {

    private BlockContents() {
    }

    /**
     * The contents of a block about to be removed.
     *
     * @param block the block, still in the world
     * @return an inventory or sign payload, or empty for a block without contents
     */
    public static @NotNull Optional<Payload> of(@NotNull Block block) {
        BlockState state = block.getState(false);
        return switch (state) {
            case Sign sign -> brokenSign(SignText.sides(sign));
            case Chest chest -> inventory(chest.getBlockInventory());
            case BlockInventoryHolder holder -> inventory(holder.getInventory());
            default -> Optional.empty();
        };
    }

    /**
     * The non-empty slots of an inventory.
     *
     * @param inventory the inventory
     * @return the slots, or empty when the inventory is empty
     */
    public static @NotNull Optional<Payload> inventory(@NotNull Inventory inventory) {
        List<Payload.Slot> slots = new ArrayList<>();
        ItemStack[] contents = inventory.getContents();
        for (int index = 0; index < contents.length; index++) {
            ItemStack item = contents[index];
            if (item != null && !item.isEmpty()) {
                slots.add(new Payload.Slot(index, ItemBytes.serialize(item)));
            }
        }
        return slots.isEmpty() ? Optional.empty() : Optional.of(new Payload.Inventory(slots));
    }

    private static @NotNull Optional<Payload> brokenSign(@NotNull SignSides sides) {
        return sides.isEmpty() ? Optional.empty() : Optional.of(new Payload.Sign(sides, SignSides.EMPTY));
    }
}
