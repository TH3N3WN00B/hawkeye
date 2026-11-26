package gg.moonrise.hawkeye.core.service.capture.block;

import gg.moonrise.hawkeye.core.service.capture.container.InventoryDiff;
import gg.moonrise.moss.spring.SpringComponent;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.block.ChiseledBookshelf;
import org.bukkit.block.DecoratedPot;
import org.bukkit.block.Jukebox;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Blocks that hold items a player puts in or takes out with a right click, without a menu: jukeboxes,
 * chiseled bookshelves, decorated pots and, from Minecraft 1.21.9, shelves ({@link Shelves}). Their
 * contents are block inventories, recorded like any container.
 */
@SpringComponent
@RequiredArgsConstructor
public final class BlockItems {

    private final @NotNull Shelves shelves;

    /**
     * Whether a block type holds items a right click changes. Cheap, so it is checked before a block
     * state is read.
     *
     * @param material the block type
     * @return {@code true} for jukeboxes, chiseled bookshelves, decorated pots and wooden shelves
     */
    public boolean holdsItems(@NotNull Material material) {
        return switch (material) {
            case JUKEBOX, CHISELED_BOOKSHELF, DECORATED_POT -> true;
            default -> shelves.isShelf(material);
        };
    }

    /**
     * A copy of the items a block holds. A jukebox holds its one record.
     *
     * @param state the block's state
     * @return the contents, empty slots as {@code null}, or empty when the block holds no such items
     */
    public @NotNull Optional<@Nullable ItemStack @NotNull []> contents(@NotNull BlockState state) {
        return switch (state) {
            case Jukebox jukebox -> Optional.of(InventoryDiff.copy(new ItemStack[] {jukebox.getRecord()}));
            case ChiseledBookshelf bookshelf -> Optional.of(InventoryDiff.copy(bookshelf.getInventory().getContents()));
            case DecoratedPot pot -> Optional.of(InventoryDiff.copy(pot.getInventory().getContents()));
            default -> shelves.contents(state);
        };
    }
}
