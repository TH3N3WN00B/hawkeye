package gg.moonrise.hawkeye.listener.block;

import gg.moonrise.hawkeye.config.file.LogCategory;
import gg.moonrise.hawkeye.core.service.capture.Actors;
import gg.moonrise.hawkeye.core.service.capture.EventRecorder;
import gg.moonrise.hawkeye.core.service.capture.block.BlockCapture;
import gg.moonrise.hawkeye.core.service.capture.block.PistonMoves;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.moss.spring.SpringComponent;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.PistonMoveReaction;
import org.bukkit.block.data.Directional;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPistonEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Pistons ({@link LogCategory#PISTONS}): a block in the way that the piston destroys (a torch, a flower,
 * sugar cane) is a break by {@link Actors#PISTON} with its contents; every moved block is a break at
 * its old position and a place at its new one, in the order {@link PistonMoves} replays the push.
 */
@SpringComponent
@RequiredArgsConstructor
public final class PistonListener implements Listener {

    private final @NotNull EventRecorder recorder;
    private final @NotNull BlockCapture blocks;

    /**
     * Records the blocks an extending piston pushes or destroys.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExtend(@NotNull BlockPistonExtendEvent event) {
        record(event, event.getBlocks(), true);
    }

    /**
     * Records the blocks a retracting sticky piston pulls or destroys.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRetract(@NotNull BlockPistonRetractEvent event) {
        record(event, event.getBlocks(), false);
    }

    private void record(@NotNull BlockPistonEvent event, @NotNull List<Block> affected, boolean extending) {
        if (!recorder.enabled(LogCategory.PISTONS, event.getBlock().getWorld()) || affected.isEmpty()) {
            return;
        }
        PistonMoves.Push push = PistonMoves.push(affected, movement(event, extending),
                block -> block.getPistonMoveReaction() == PistonMoveReaction.BREAK);
        for (Block destroyed : push.destroyed()) {
            blocks.broken(Actors.PISTON, destroyed);
        }
        for (PistonMoves.Move move : push.moves()) {
            Block from = move.from();
            recorder.record(Action.BLOCK_BREAK, Actors.PISTON, from.getWorld(), from.getX(), from.getY(), from.getZ(),
                    move.data().getAsString(), EventRecorder.AIR, 0, null);
            recorder.block(Action.BLOCK_PLACE, Actors.PISTON, move.to(), move.data(), move.replaced(), null);
        }
    }

    /**
     * The direction the blocks move: the piston's facing when it extends, the opposite when it
     * retracts. Read from the piston itself (a retracting piston is a moving piston that keeps its
     * facing) rather than from the event's direction, whose meaning differs between the two events.
     */
    private static @NotNull BlockFace movement(@NotNull BlockPistonEvent event, boolean extending) {
        if (event.getBlock().getBlockData() instanceof Directional piston) {
            return extending ? piston.getFacing() : piston.getFacing().getOppositeFace();
        }
        return event.getDirection();
    }
}
