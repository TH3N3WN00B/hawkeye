package gg.moonrise.hawkeye.listener;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.mockbukkit.mockbukkit.inventory.ChestInventoryMock;
import org.mockbukkit.mockbukkit.inventory.DropperInventoryMock;
import org.mockbukkit.mockbukkit.inventory.HopperInventoryMock;

/**
 * MockBukkit inventories that answer {@code Inventory#getHolder(boolean)}, which MockBukkit 4.120 does
 * not implement and the container listeners call to avoid a block state snapshot.
 */
public final class HeldInventories {

    private HeldInventories() {
    }

    /**
     * A 27-slot chest inventory.
     *
     * @param holder its holder, or {@code null} for a virtual inventory
     * @return the inventory
     */
    public static @NotNull Inventory chest(@Nullable InventoryHolder holder) {
        return new Chest(holder);
    }

    /**
     * A hopper inventory.
     *
     * @param holder its holder
     * @return the inventory
     */
    public static @NotNull Inventory hopper(@Nullable InventoryHolder holder) {
        return new Hopper(holder);
    }

    /**
     * A dropper inventory.
     *
     * @param holder its holder
     * @return the inventory
     */
    public static @NotNull Inventory dropper(@Nullable InventoryHolder holder) {
        return new Dropper(holder);
    }

    private static final class Chest extends ChestInventoryMock {

        private Chest(@Nullable InventoryHolder holder) {
            super(holder, 27);
        }

        @Override
        public @Nullable InventoryHolder getHolder(boolean useSnapshot) {
            return getHolder();
        }
    }

    private static final class Hopper extends HopperInventoryMock {

        private Hopper(@Nullable InventoryHolder holder) {
            super(holder);
        }

        @Override
        public @Nullable InventoryHolder getHolder(boolean useSnapshot) {
            return getHolder();
        }
    }

    private static final class Dropper extends DropperInventoryMock {

        private Dropper(@Nullable InventoryHolder holder) {
            super(holder);
        }

        @Override
        public @Nullable InventoryHolder getHolder(boolean useSnapshot) {
            return getHolder();
        }
    }
}
