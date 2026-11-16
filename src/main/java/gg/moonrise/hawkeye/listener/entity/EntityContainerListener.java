package gg.moonrise.hawkeye.listener.entity;

import gg.moonrise.hawkeye.config.file.LogCategory;
import gg.moonrise.hawkeye.core.service.capture.Actors;
import gg.moonrise.hawkeye.core.service.capture.EventRecorder;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.moss.spring.SpringComponent;
import io.papermc.paper.event.player.PlayerItemFrameChangeEvent;
import io.papermc.paper.event.player.PlayerItemFrameChangeEvent.ItemFrameChangeAction;
import lombok.RequiredArgsConstructor;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.ItemFrame;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * Item frames and armour stands as containers ({@link LogCategory#CONTAINERS}): an item put into a
 * frame or onto a stand is a {@link Action#CONTAINER_DEPOSIT}, one taken off a
 * {@link Action#CONTAINER_WITHDRAW}, at the entity's block. A rollback puts the item back into, or takes
 * it out of, the frame or stand at that block. Rotating a frame's item changes nothing and is skipped.
 */
@SpringComponent
@RequiredArgsConstructor
public final class EntityContainerListener implements Listener {

    private final @NotNull EventRecorder recorder;

    /**
     * Records an item put into or taken out of an item frame. A frame holds one item, whatever the stack
     * in the player's hand.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemFrameChange(@NotNull PlayerItemFrameChangeEvent event) {
        ItemFrame frame = event.getItemFrame();
        ItemFrameChangeAction change = event.getAction();
        if (change == ItemFrameChangeAction.ROTATE || !recorder.enabled(LogCategory.CONTAINERS, frame.getWorld())) {
            return;
        }
        Action action = change == ItemFrameChangeAction.PLACE ? Action.CONTAINER_DEPOSIT : Action.CONTAINER_WITHDRAW;
        recorder.container(action, Actors.of(event.getPlayer()), frame.getLocation(), event.getItemStack(), 1);
    }

    /**
     * Records a player swapping items with an armour stand: the stand's item is withdrawn and one of the
     * player's deposited (either may be empty). A stand slot that holds an item is not swapped with a
     * stack of more than one.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onArmorStandManipulate(@NotNull PlayerArmorStandManipulateEvent event) {
        ArmorStand stand = event.getRightClicked();
        ItemStack standItem = event.getArmorStandItem();
        ItemStack playerItem = event.getPlayerItem();
        boolean refused = !standItem.isEmpty() && playerItem.getAmount() > 1;
        if (refused || !recorder.enabled(LogCategory.CONTAINERS, stand.getWorld())) {
            return;
        }
        Actor actor = Actors.of(event.getPlayer());
        Location location = stand.getLocation();
        recorder.container(Action.CONTAINER_WITHDRAW, actor, location, standItem, standItem.getAmount());
        recorder.container(Action.CONTAINER_DEPOSIT, actor, location, playerItem, 1);
    }
}
