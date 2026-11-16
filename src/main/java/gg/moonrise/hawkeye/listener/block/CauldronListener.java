package gg.moonrise.hawkeye.listener.block;

import gg.moonrise.hawkeye.config.file.LogCategory;
import gg.moonrise.hawkeye.core.service.capture.Actors;
import gg.moonrise.hawkeye.core.service.capture.EventRecorder;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.moss.spring.SpringComponent;
import lombok.RequiredArgsConstructor;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.CauldronLevelChangeEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Cauldrons filling and emptying: a {@link Action#BLOCK_CHANGE} from the old state to the new one. A
 * player with a bucket or bottle, or washing an item, is {@link LogCategory#BUCKETS}; evaporation, rain,
 * snow and dripstone ({@link Actors#NATURE}) and mobs ({@link LogCategory#NATURAL}) are natural.
 */
@SpringComponent
@RequiredArgsConstructor
public final class CauldronListener implements Listener {

    private final @NotNull EventRecorder recorder;

    /**
     * Records the change before the world applies it.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLevelChange(@NotNull CauldronLevelChangeEvent event) {
        Block block = event.getBlock();
        Entity entity = event.getEntity();
        if (!recorder.enabled(entity instanceof Player ? LogCategory.BUCKETS : LogCategory.NATURAL,
                block.getWorld())) {
            return;
        }
        BlockData before = block.getBlockData();
        BlockData after = event.getNewState().getBlockData();
        if (!after.equals(before)) {
            recorder.block(Action.BLOCK_CHANGE, entity == null ? Actors.NATURE : Actors.of(entity), block, after,
                    before, null);
        }
    }
}
