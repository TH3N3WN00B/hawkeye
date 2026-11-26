package gg.moonrise.hawkeye.core.service.capture.block;

import java.util.Optional;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Shelves (Minecraft 1.21.9 and later), the item-holding blocks {@link BlockItems} cannot name on older
 * servers. {@code PaperShelves} reads them where the server has them, {@link NoShelves} stands in elsewhere.
 */
public interface Shelves {

    /**
     * Whether a block type is a shelf.
     *
     * @param material the block type
     * @return {@code true} for a wooden shelf
     */
    boolean isShelf(@NotNull Material material);

    /**
     * A copy of the items a shelf holds.
     *
     * @param state the block's state
     * @return the contents, empty slots as {@code null}, or empty when the block is no shelf
     */
    @NotNull Optional<@Nullable ItemStack @NotNull []> contents(@NotNull BlockState state);
}
