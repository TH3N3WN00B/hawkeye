package gg.moonrise.hawkeye.listener;

import net.kyori.adventure.text.Component;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.mockbukkit.mockbukkit.inventory.ChestInventoryMock;
import org.mockbukkit.mockbukkit.inventory.DropperInventoryMock;
import org.mockbukkit.mockbukkit.inventory.HopperInventoryMock;
import org.mockbukkit.mockbukkit.inventory.InventoryMock;

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
     * A 27-slot chest inventory with a title, as a plugin creates one with
     * {@code Bukkit.createInventory(holder, 27, title)}.
     *
     * @param holder its holder, or {@code null}
     * @param title  the title its views show
     * @return the inventory
     */
    public static @NotNull Inventory chest(@Nullable InventoryHolder holder, @NotNull Component title) {
        Chest chest = new Chest(holder);
        chest.setCustomTitle(title);
        return chest;
    }

    /**
     * An inventory of any type (an ender chest, another player's inventory, a workbench), since
     * MockBukkit's own ({@code getEnderChest()}, {@code createInventory}) do not answer
     * {@code getHolder(boolean)} either.
     *
     * @param holder its holder, or {@code null}
     * @param type   its type
     * @return the inventory
     */
    public static @NotNull Inventory typed(@Nullable InventoryHolder holder, @NotNull InventoryType type) {
        return new Typed(holder, type);
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

    private static final class Typed extends InventoryMock {

        private Typed(@Nullable InventoryHolder holder, @NotNull InventoryType type) {
            super(holder, type);
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
