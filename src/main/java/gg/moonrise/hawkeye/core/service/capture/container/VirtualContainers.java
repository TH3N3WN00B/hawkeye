package gg.moonrise.hawkeye.core.service.capture.container;

import gg.moonrise.hawkeye.core.service.capture.text.PlainText;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.bukkit.block.BlockState;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Which open inventories are virtual containers: inventories that store items but are no container in
 * the world ({@link ContainerLocations} finds none), so their transactions are recorded where the
 * player stands. They are an ender chest (its holder is the owner), another player's inventory (an
 * /invsee-style view) and a plugin's own storage inventory (player vaults, backpacks, menus: a chest,
 * barrel, shulker box, hopper, dispenser or dropper inventory whose holder is no block, double chest or
 * entity). Inventories that do not keep what is put in (workbench, anvil, merchant, furnace, ...) are
 * not virtual containers.
 */
public final class VirtualContainers {

    /** The longest label taken from an inventory title. */
    public static final int MAX_TITLE = 64;

    private static final Set<InventoryType> STORAGE = EnumSet.of(InventoryType.CHEST, InventoryType.BARREL,
            InventoryType.SHULKER_BOX, InventoryType.HOPPER, InventoryType.DISPENSER, InventoryType.DROPPER);

    private VirtualContainers() {
    }

    /**
     * The label of a view's top inventory when it is a virtual container.
     *
     * @param view   the open view
     * @param viewer the player looking at it
     * @return {@code ender_chest}, {@code ender_chest (<owner>)}, {@code inventory (<owner>)} or a plugin
     *         inventory's title on one line (its type in lower case when the title is blank); empty when
     *         the top inventory is not a virtual container
     */
    public static @NotNull Optional<String> label(@NotNull InventoryView view, @NotNull Player viewer) {
        Inventory top = view.getTopInventory();
        InventoryHolder holder = top.getHolder(false);
        if (ContainerLocations.of(holder).isPresent()) {
            return Optional.empty();
        }
        InventoryType type = top.getType();
        if (type == InventoryType.ENDER_CHEST) {
            return Optional.of(holder instanceof HumanEntity owner && !isViewer(owner, viewer)
                    ? "ender_chest (" + owner.getName() + ")" : "ender_chest");
        }
        if (type == InventoryType.PLAYER) {
            return holder instanceof Player owner && !isViewer(owner, viewer)
                    ? Optional.of("inventory (" + owner.getName() + ")") : Optional.empty();
        }
        if (STORAGE.contains(type) && pluginHolder(holder)) {
            String title = PlainText.capped(PlainText.oneLine(view.title()), MAX_TITLE);
            return Optional.of(title.isBlank() ? type.name().toLowerCase(Locale.ROOT) : title);
        }
        return Optional.empty();
    }

    private static boolean isViewer(@NotNull HumanEntity owner, @NotNull Player viewer) {
        return owner.getUniqueId().equals(viewer.getUniqueId());
    }

    /** Whether a holder is none or a plugin's own: not a block, a double chest or an entity. */
    private static boolean pluginHolder(@Nullable InventoryHolder holder) {
        return !(holder instanceof Entity || holder instanceof BlockState || holder instanceof BlockInventoryHolder
                || holder instanceof DoubleChest);
    }
}
