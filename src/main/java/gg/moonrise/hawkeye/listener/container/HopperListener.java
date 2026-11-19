package gg.moonrise.hawkeye.listener.container;

import gg.moonrise.hawkeye.config.HawkeyeConfigProvider;
import gg.moonrise.hawkeye.config.file.LogCategory;
import gg.moonrise.hawkeye.core.service.capture.Actors;
import gg.moonrise.hawkeye.core.service.capture.EventRecorder;
import gg.moonrise.hawkeye.core.service.capture.container.ContainerLocations;
import gg.moonrise.hawkeye.core.service.capture.container.InventorySpace;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.moss.spring.SpringComponent;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.bukkit.Location;
import org.bukkit.entity.minecart.HopperMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * Automatic transfers ({@link LogCategory#HOPPERS}, off by default): an item a hopper, hopper minecart
 * or dropper moves is a withdrawal at the source container block and a deposit at the destination
 * container block, by {@link Actors#HOPPER} or {@link Actors#DISPENSER}. Ends of the transfer that are
 * not blocks (minecarts) are not recorded. A ground item a hopper or hopper minecart picks up is a
 * deposit at the hopper (the minecart's block) by {@link Actors#HOPPER}, counting only what fits. These
 * events are among the busiest on a server, so the global switch is read before anything else.
 */
@SpringComponent
@RequiredArgsConstructor
public final class HopperListener implements Listener {

    private final @NotNull EventRecorder recorder;
    private final @NotNull HawkeyeConfigProvider configProvider;

    /**
     * Records both ends of a transfer.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(@NotNull InventoryMoveItemEvent event) {
        if (!configProvider.get().logging().enabled(LogCategory.HOPPERS)) {
            return;
        }
        Optional<Location> source = ContainerLocations.of(event.getSource());
        Optional<Location> destination = ContainerLocations.of(event.getDestination());
        Optional<Location> any = source.or(() -> destination);
        if (any.isEmpty() || any.get().getWorld() == null
                || !recorder.enabled(LogCategory.HOPPERS, any.get().getWorld())) {
            return;
        }
        Actor actor = event.getInitiator().getType() == InventoryType.DROPPER ? Actors.DISPENSER : Actors.HOPPER;
        ItemStack item = event.getItem();
        source.ifPresent(at -> recorder.container(Action.CONTAINER_WITHDRAW, actor, at, item, item.getAmount()));
        destination.ifPresent(at -> recorder.container(Action.CONTAINER_DEPOSIT, actor, at, item, item.getAmount()));
    }

    /**
     * Records a ground item a hopper or hopper minecart picks up. The event fires before the item
     * moves, and the hopper takes only what fits (it retries every tick while nothing fits), so only
     * that part is recorded.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(@NotNull InventoryPickupItemEvent event) {
        if (!configProvider.get().logging().enabled(LogCategory.HOPPERS)) {
            return;
        }
        Inventory inventory = event.getInventory();
        Optional<Location> hopper = ContainerLocations.of(inventory).or(() -> minecart(inventory));
        if (hopper.isEmpty() || hopper.get().getWorld() == null
                || !recorder.enabled(LogCategory.HOPPERS, hopper.get().getWorld())) {
            return;
        }
        ItemStack item = event.getItem().getItemStack();
        int moved = InventorySpace.room(inventory, item);
        if (moved > 0) {
            recorder.container(Action.CONTAINER_DEPOSIT, Actors.HOPPER, hopper.get(), item, moved);
        }
    }

    private static @NotNull Optional<Location> minecart(@NotNull Inventory inventory) {
        return inventory.getHolder(false) instanceof HopperMinecart minecart
                ? Optional.of(minecart.getLocation().toBlockLocation()) : Optional.empty();
    }
}
