package gg.moonrise.hawkeye.core.service.capture.container;

import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.ChestBoat;
import org.bukkit.entity.ChestedHorse;
import org.bukkit.entity.Entity;
import org.bukkit.entity.minecart.HopperMinecart;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Where a container transaction is recorded: the block of a container, the left half of a double
 * chest, or the block an entity that carries a container stands in (minecarts with a chest or hopper,
 * chest boats and rafts, donkeys, mules and llamas). Other entities with an inventory (players,
 * villagers, horses without a chest slot) and virtual inventories are not recorded.
 */
public final class ContainerLocations {

    private ContainerLocations() {
    }

    /**
     * The block location of an inventory. Reads the holder without a snapshot, which is cheap enough
     * for hopper transfers.
     *
     * @param inventory the inventory
     * @return the container block, or empty when the inventory belongs to no container
     */
    public static @NotNull Optional<Location> of(@NotNull Inventory inventory) {
        return of(inventory.getHolder(false));
    }

    /**
     * The block location of an inventory holder.
     *
     * @param holder the holder, or {@code null}
     * @return the container block, or empty when the holder is not a container
     */
    public static @NotNull Optional<Location> of(@Nullable InventoryHolder holder) {
        return switch (holder) {
            case DoubleChest chest -> Optional.of(chest.getLeftSide(false) instanceof BlockState left
                    ? left.getLocation() : chest.getLocation().toBlockLocation());
            case Container container -> Optional.of(container.getLocation());
            case Entity entity when carriesContainer(entity) -> Optional.of(entity.getLocation().toBlockLocation());
            case null, default -> Optional.empty();
        };
    }

    /**
     * Whether an entity carries a container whose transactions are recorded at its block.
     *
     * @param entity the entity
     * @return {@code true} for minecarts with a chest or hopper, chest boats and rafts, and chested horses
     */
    public static boolean carriesContainer(@NotNull Entity entity) {
        return entity instanceof StorageMinecart || entity instanceof HopperMinecart || entity instanceof ChestBoat
                || entity instanceof ChestedHorse;
    }
}
