package gg.moonrise.hawkeye.listener.player;

import gg.moonrise.hawkeye.config.file.LogCategory;
import gg.moonrise.hawkeye.core.service.capture.Actors;
import gg.moonrise.hawkeye.core.service.capture.EventRecorder;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.moss.spring.SpringComponent;
import lombok.RequiredArgsConstructor;
import org.bukkit.Location;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerPickupArrowEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * Items players drop ({@link LogCategory#ITEM_DROPS}) and pick up ({@link LogCategory#ITEM_PICKUPS}),
 * at the item's position, with the count that actually moved. Arrows and tridents picked up from the
 * ground are pickups at the arrow; what a player drops on death (keep-inventory off) is one drop per
 * stack where they died.
 */
@SpringComponent
@RequiredArgsConstructor
public final class ItemListener implements Listener {

    private final @NotNull EventRecorder recorder;

    /**
     * Records a dropped item.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(@NotNull PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        if (recorder.enabled(LogCategory.ITEM_DROPS, player.getWorld())) {
            Item drop = event.getItemDrop();
            recorder.item(Action.ITEM_DROP, Actors.of(player), drop.getLocation(), drop.getItemStack());
        }
    }

    /**
     * Records an item a player picks up; what stays on the ground is not counted.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(@NotNull EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)
                || !recorder.enabled(LogCategory.ITEM_PICKUPS, player.getWorld())) {
            return;
        }
        Item item = event.getItem();
        ItemStack stack = item.getItemStack();
        int picked = stack.getAmount() - event.getRemaining();
        if (picked > 0) {
            recorder.item(Action.ITEM_PICKUP, Actors.of(player), item.getLocation(), stack.asQuantity(picked));
        }
    }

    /**
     * Records an arrow or trident a player picks up from the ground.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onArrowPickup(@NotNull PlayerPickupArrowEvent event) {
        Player player = event.getPlayer();
        if (recorder.enabled(LogCategory.ITEM_PICKUPS, player.getWorld())) {
            AbstractArrow arrow = event.getArrow();
            recorder.item(Action.ITEM_PICKUP, Actors.of(player), arrow.getLocation(), arrow.getItemStack());
        }
    }

    /**
     * Records what a player drops on death, one drop per stack. The drops are final at this priority.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(@NotNull PlayerDeathEvent event) {
        Player player = event.getPlayer();
        if (event.getKeepInventory() || !recorder.enabled(LogCategory.ITEM_DROPS, player.getWorld())) {
            return;
        }
        Actor actor = Actors.of(player);
        Location location = player.getLocation();
        for (ItemStack drop : event.getDrops()) {
            if (drop != null) {
                recorder.item(Action.ITEM_DROP, actor, location, drop);
            }
        }
    }
}
