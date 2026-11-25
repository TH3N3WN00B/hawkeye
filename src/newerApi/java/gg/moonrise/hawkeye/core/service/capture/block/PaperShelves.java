package gg.moonrise.hawkeye.core.service.capture.block;

import gg.moonrise.hawkeye.core.service.capture.container.InventoryDiff;
import gg.moonrise.hawkeye.util.compat.ConditionalOnApi;
import gg.moonrise.hawkeye.util.compat.NewerApi;
import gg.moonrise.moss.spring.SpringComponent;
import java.util.Optional;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.BlockState;
import org.bukkit.block.Shelf;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** {@link Shelves} on Minecraft 1.21.9 and later: the wooden shelves and their block inventories. */
@SpringComponent
@ConditionalOnApi(NewerApi.SHELF)
public final class PaperShelves implements Shelves {

    @Override
    public boolean isShelf(@NotNull Material material) {
        return Tag.WOODEN_SHELVES.isTagged(material);
    }

    @Override
    public @NotNull Optional<@Nullable ItemStack @NotNull []> contents(@NotNull BlockState state) {
        return state instanceof Shelf shelf ? Optional.of(InventoryDiff.copy(shelf.getInventory().getContents()))
                : Optional.empty();
    }
}
