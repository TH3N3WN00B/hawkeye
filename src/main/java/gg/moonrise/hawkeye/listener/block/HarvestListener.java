package gg.moonrise.hawkeye.listener.block;

import gg.moonrise.hawkeye.config.file.LogCategory;
import gg.moonrise.hawkeye.core.service.capture.Actors;
import gg.moonrise.hawkeye.core.service.capture.EventRecorder;
import gg.moonrise.hawkeye.core.service.capture.block.BlockRecheck;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.moss.spring.SpringComponent;
import io.papermc.paper.event.block.PlayerShearBlockEvent;
import io.papermc.paper.event.player.PlayerFlowerPotManipulateEvent;
import lombok.RequiredArgsConstructor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerHarvestBlockEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Players changing a block by hand: picking sweet berries and shearing a pumpkin or beehive
 * ({@link LogCategory#INTERACTIONS}), and putting a plant into or taking it out of a flower pot
 * ({@link LogCategory#BLOCK_PLACE}). The world applies these after the event, so the block is read
 * again on its own thread a tick later and {@link Action#BLOCK_CHANGE} is recorded only when it changed.
 * Cave vines are left to the entity-change event they also raise.
 */
@SpringComponent
@RequiredArgsConstructor
public final class HarvestListener implements Listener {

    private final @NotNull EventRecorder recorder;
    private final @NotNull BlockRecheck recheck;

    /**
     * Records a harvested berry bush.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHarvest(@NotNull PlayerHarvestBlockEvent event) {
        Block block = event.getHarvestedBlock();
        Material material = block.getType();
        if (material != Material.CAVE_VINES && material != Material.CAVE_VINES_PLANT) {
            watch(LogCategory.INTERACTIONS, event.getPlayer(), block);
        }
    }

    /**
     * Records a sheared pumpkin or beehive.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShear(@NotNull PlayerShearBlockEvent event) {
        watch(LogCategory.INTERACTIONS, event.getPlayer(), event.getBlock());
    }

    /**
     * Records a flower pot filled or emptied.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFlowerPot(@NotNull PlayerFlowerPotManipulateEvent event) {
        watch(LogCategory.BLOCK_PLACE, event.getPlayer(), event.getFlowerpot());
    }

    private void watch(@NotNull LogCategory category, @NotNull Player player, @NotNull Block block) {
        if (!recorder.enabled(category, block.getWorld())) {
            return;
        }
        Actor actor = Actors.of(player);
        recheck.afterTick(block, block.getBlockData(), (changed, before, after) ->
                recorder.block(Action.BLOCK_CHANGE, actor, changed, after, before, null));
    }
}
