package gg.moonrise.hawkeye.listener.player;

import gg.moonrise.hawkeye.config.file.LogCategory;
import gg.moonrise.hawkeye.core.service.capture.Actors;
import gg.moonrise.hawkeye.core.service.capture.EventRecorder;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.util.compat.ConditionalOnMissingApi;
import gg.moonrise.hawkeye.util.compat.NewerApi;
import gg.moonrise.moss.spring.SpringComponent;
import lombok.RequiredArgsConstructor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Crafted results players take ({@link LogCategory#CRAFTING}) on servers before Paper 1.21.11, which lack
 * Paper's crafted-item event ({@code CraftListener} records them there). Bukkit's {@link CraftItemEvent}
 * comes before the server applies the click, so the count is worked out from the click as the server
 * applies it to a result slot: a left or right click takes one craft's result when the cursor can hold it,
 * a number key or the off-hand key when that slot is empty, the drop keys one craft with an empty cursor;
 * a shift-click repeats the craft while every ingredient lasts (each craft uses one item from every filled
 * grid slot) and the result fits into the player's inventory.
 */
@SpringComponent
@ConditionalOnMissingApi(NewerApi.ITEM_CRAFTED_EVENT)
@RequiredArgsConstructor
public final class CraftClickListener implements Listener {

    private final @NotNull EventRecorder recorder;

    /**
     * Records the items a click on a crafting result takes, where the player stands.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(@NotNull CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || event.getAction() == InventoryAction.NOTHING
                || !recorder.enabled(LogCategory.CRAFTING, player.getWorld())) {
            return;
        }
        ItemStack result = event.getInventory().getResult();
        if (empty(result)) {
            return;
        }
        int crafted = crafted(event, player.getInventory(), result);
        if (crafted > 0) {
            recorder.item(Action.ITEM_CRAFT, Actors.of(player), player.getLocation(), result.asQuantity(crafted));
        }
    }

    private static int crafted(@NotNull CraftItemEvent event, @NotNull PlayerInventory inventory,
            @NotNull ItemStack result) {
        int once = result.getAmount();
        return switch (event.getClick()) {
            case LEFT, RIGHT -> fitsOn(event.getCursor(), result) ? once : 0;
            case NUMBER_KEY -> empty(inventory.getItem(event.getHotbarButton())) ? once : 0;
            case SWAP_OFFHAND -> empty(inventory.getItemInOffHand()) ? once : 0;
            case DROP, CONTROL_DROP -> empty(event.getCursor()) ? once : 0;
            case SHIFT_LEFT, SHIFT_RIGHT -> Math.min(once * crafts(event.getInventory()),
                    room(inventory, result) / once * once);
            default -> 0;
        };
    }

    /** How often the grid can make its recipe: every craft uses one item from each filled slot. */
    private static int crafts(@NotNull CraftingInventory grid) {
        int crafts = Integer.MAX_VALUE;
        for (ItemStack ingredient : grid.getMatrix()) {
            if (!empty(ingredient)) {
                crafts = Math.min(crafts, ingredient.getAmount());
            }
        }
        return crafts == Integer.MAX_VALUE ? 0 : crafts;
    }

    /** How many of the result the player's main inventory and hotbar can take. */
    private static int room(@NotNull PlayerInventory inventory, @NotNull ItemStack result) {
        int max = result.getMaxStackSize();
        int room = 0;
        for (ItemStack slot : inventory.getStorageContents()) {
            if (empty(slot)) {
                room += max;
            } else if (slot.isSimilar(result)) {
                room += Math.max(0, max - slot.getAmount());
            }
        }
        return room;
    }

    private static boolean fitsOn(@Nullable ItemStack cursor, @NotNull ItemStack result) {
        return empty(cursor)
                || cursor.isSimilar(result) && cursor.getAmount() + result.getAmount() <= cursor.getMaxStackSize();
    }

    private static boolean empty(@Nullable ItemStack item) {
        return item == null || item.getType().isAir();
    }
}
