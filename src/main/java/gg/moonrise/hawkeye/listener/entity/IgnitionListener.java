package gg.moonrise.hawkeye.listener.entity;

import gg.moonrise.hawkeye.config.file.LogCategory;
import gg.moonrise.hawkeye.core.service.capture.Actors;
import gg.moonrise.hawkeye.core.service.capture.EventRecorder;
import gg.moonrise.moss.spring.SpringComponent;
import lombok.RequiredArgsConstructor;
import org.bukkit.Tag;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * Creepers a player ignites with flint and steel or a fire charge ({@link LogCategory#EXPLOSIONS}): the
 * creeper is tagged with the player ({@link Actors#tag}), so the blocks its explosion destroys count for
 * them instead of {@code #creeper}.
 */
@SpringComponent
@RequiredArgsConstructor
public final class IgnitionListener implements Listener {

    private final @NotNull EventRecorder recorder;

    /**
     * Tags a creeper clicked with an igniter.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(@NotNull PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Creeper creeper)) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItem(event.getHand());
        if (Tag.ITEMS_CREEPER_IGNITERS.isTagged(item.getType())
                && recorder.enabled(LogCategory.EXPLOSIONS, creeper.getWorld())) {
            Actors.tag(creeper, Actors.of(player));
        }
    }
}
