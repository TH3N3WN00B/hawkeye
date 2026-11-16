package gg.moonrise.hawkeye.listener.block;

import gg.moonrise.hawkeye.config.file.LogCategory;
import gg.moonrise.hawkeye.core.service.capture.Actors;
import gg.moonrise.hawkeye.core.service.capture.EventRecorder;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.moss.spring.SpringComponent;
import lombok.RequiredArgsConstructor;
import org.bukkit.block.BlockState;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Players placing blocks ({@link LogCategory#BLOCK_PLACE}): the placed state and the state it
 * replaced, for every block of a multi-block placement (beds, doors, tall plants).
 */
@SpringComponent
@RequiredArgsConstructor
public final class BlockPlaceListener implements Listener {

    private final @NotNull EventRecorder recorder;

    /**
     * Records a placement. {@link BlockMultiPlaceEvent} shares this event's handler list, so it arrives
     * here too.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(@NotNull BlockPlaceEvent event) {
        if (!event.canBuild() || !recorder.enabled(LogCategory.BLOCK_PLACE, event.getBlock().getWorld())) {
            return;
        }
        Actor actor = Actors.of(event.getPlayer());
        if (event instanceof BlockMultiPlaceEvent multi) {
            for (BlockState replaced : multi.getReplacedBlockStates()) {
                recorder.blockPlace(actor, replaced, replaced.getBlock().getBlockData());
            }
        } else {
            recorder.blockPlace(actor, event.getBlockReplacedState(), event.getBlockPlaced().getBlockData());
        }
    }
}
