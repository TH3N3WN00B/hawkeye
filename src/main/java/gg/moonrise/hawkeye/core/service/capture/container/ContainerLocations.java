package gg.moonrise.hawkeye.core.service.capture.container;

import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Where a container transaction is recorded: the block of a container, or the left half of a double
 * chest. Inventories of entities (minecarts, boats, animals) and virtual inventories have no block
 * and are not recorded.
 */
public final class ContainerLocations {

    private ContainerLocations() {
    }

    /**
     * The block location of an inventory. Reads the holder without a snapshot, which is cheap enough
     * for hopper transfers.
     *
     * @param inventory the inventory
     * @return the container block, or empty when the inventory belongs to no container block
     */
    public static @NotNull Optional<Location> of(@NotNull Inventory inventory) {
        return of(inventory.getHolder(false));
    }

    /**
     * The block location of an inventory holder.
     *
     * @param holder the holder, or {@code null}
     * @return the container block, or empty when the holder is not a container block
     */
    public static @NotNull Optional<Location> of(@Nullable InventoryHolder holder) {
        return switch (holder) {
            case DoubleChest chest -> Optional.of(chest.getLeftSide(false) instanceof BlockState left
                    ? left.getLocation() : chest.getLocation().toBlockLocation());
            case Container container -> Optional.of(container.getLocation());
            case null, default -> Optional.empty();
        };
    }
}
