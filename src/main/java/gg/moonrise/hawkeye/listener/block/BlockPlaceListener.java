package gg.moonrise.hawkeye.listener.block;

import gg.moonrise.hawkeye.config.file.LogCategory;
import gg.moonrise.hawkeye.core.service.capture.Actors;
import gg.moonrise.hawkeye.core.service.capture.CauseTracker;
import gg.moonrise.hawkeye.core.service.capture.EventRecorder;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.moss.spring.SpringComponent;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Players placing blocks ({@link LogCategory#BLOCK_PLACE}): the placed state and the state it
 * replaced, for every block of a multi-block placement (beds, doors, tall plants). Every placed block
 * is also remembered under its player ({@link CauseTracker#remember}), so what it does right away (a
 * sponge soaking up water, a golem or wither it completes) counts for them, and placed TNT keeps its
 * placer for whoever or whatever lights it later ({@link CauseTracker#rememberTnt}).
 */
@SpringComponent
@RequiredArgsConstructor
public final class BlockPlaceListener implements Listener {

    private final @NotNull EventRecorder recorder;
    private final @NotNull CauseTracker causes;

    /**
     * Records a placement. {@link BlockMultiPlaceEvent} shares this event's handler list, so it arrives
     * here too.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(@NotNull BlockPlaceEvent event) {
        if (!event.canBuild()) {
            return;
        }
        Actor actor = Actors.of(event.getPlayer());
        List<BlockState> replaced = event instanceof BlockMultiPlaceEvent multi ? multi.getReplacedBlockStates()
                : List.of(event.getBlockReplacedState());
        for (BlockState state : replaced) {
            Block block = state.getBlock();
            causes.remember(block, actor);
            if (block.getType() == Material.TNT) {
                causes.rememberTnt(block, actor);
            }
        }
        if (!recorder.enabled(LogCategory.BLOCK_PLACE, event.getBlock().getWorld())) {
            return;
        }
        for (BlockState state : replaced) {
            recorder.blockPlace(actor, state, state.getBlock().getBlockData());
        }
    }
}
