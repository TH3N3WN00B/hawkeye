package gg.moonrise.hawkeye.core.service.capture.block;

import gg.moonrise.hawkeye.util.compat.ConditionalOnMissingApi;
import gg.moonrise.hawkeye.util.compat.NewerApi;
import gg.moonrise.moss.spring.SpringComponent;
import java.util.Optional;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** {@link Shelves} on a server from before Minecraft 1.21.9, which has none. */
@SpringComponent
@ConditionalOnMissingApi(NewerApi.SHELF)
public final class NoShelves implements Shelves {

    @Override
    public boolean isShelf(@NotNull Material material) {
        return false;
    }

    @Override
    public @NotNull Optional<@Nullable ItemStack @NotNull []> contents(@NotNull BlockState state) {
        return Optional.empty();
    }
}
