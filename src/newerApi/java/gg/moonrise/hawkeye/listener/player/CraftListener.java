package gg.moonrise.hawkeye.listener.player;

import gg.moonrise.hawkeye.config.file.LogCategory;
import gg.moonrise.hawkeye.core.service.capture.Actors;
import gg.moonrise.hawkeye.core.service.capture.EventRecorder;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.util.compat.ConditionalOnApi;
import gg.moonrise.hawkeye.util.compat.NewerApi;
import gg.moonrise.moss.spring.SpringComponent;
import io.papermc.paper.event.inventory.ItemCraftedEvent;
import lombok.RequiredArgsConstructor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.NotNull;

/**
 * Crafted results players take ({@link LogCategory#CRAFTING}), recorded where the player stands with the
 * count Paper reports after the craft (Paper 1.21.11 and later; {@link CraftClickListener} before).
 */
@SpringComponent
@ConditionalOnApi(NewerApi.ITEM_CRAFTED_EVENT)
@RequiredArgsConstructor
public final class CraftListener implements Listener {

    private final @NotNull EventRecorder recorder;

    /**
     * Records a crafted item the player takes from the result slot, with the crafted count.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(@NotNull ItemCraftedEvent event) {
        Player player = event.getPlayer();
        if (recorder.enabled(LogCategory.CRAFTING, player.getWorld())) {
            recorder.item(Action.ITEM_CRAFT, Actors.of(player), player.getLocation(), event.getCraftedItem());
        }
    }
}
