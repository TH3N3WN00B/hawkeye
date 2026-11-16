package gg.moonrise.hawkeye.listener.block;

import gg.moonrise.hawkeye.config.HawkeyeConfigProvider;
import gg.moonrise.hawkeye.config.file.LogCategory;
import gg.moonrise.hawkeye.core.service.capture.Actors;
import gg.moonrise.hawkeye.core.service.capture.BlockCapture;
import gg.moonrise.hawkeye.core.service.capture.BlockChanges;
import gg.moonrise.hawkeye.core.service.capture.CauseTracker;
import gg.moonrise.hawkeye.core.service.capture.EventRecorder;
import gg.moonrise.hawkeye.core.service.capture.FluidFlows;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.moss.spring.SpringComponent;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.SpongeAbsorbEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Water and lava ({@link LogCategory#FLUIDS}): a fluid flowing into another block is a place of the
 * flowing state it leaves there ({@link FluidFlows}; never a source, so a rollback cannot create one)
 * by {@link Actors#WATER} or {@link Actors#LAVA}, and water meeting lava (cobblestone, stone, obsidian)
 * is a change blamed on the fluid that was not replaced. With {@code liquid-tracking}, a flow and the
 * stone it forms count for the player who poured the fluid, and pass that player on to the block they
 * flow into. Flow within the same fluid only changes its level and is not recorded. A sponge soaking up
 * water is a break of each water block (or a change of each drained waterlogged block) by whoever placed
 * or uncovered the sponge, or {@link Actors#SPONGE}.
 */
@SpringComponent
@RequiredArgsConstructor
public final class FluidListener implements Listener {

    /** The sides a fluid that forms stone can come from: lava and water never react from below. */
    private static final BlockFace[] TOUCHING = {BlockFace.UP, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST,
            BlockFace.WEST};

    private final @NotNull EventRecorder recorder;
    private final @NotNull BlockCapture blocks;
    private final @NotNull CauseTracker causes;
    private final @NotNull HawkeyeConfigProvider configProvider;

    /**
     * Records a fluid flowing into a block.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFlow(@NotNull BlockFromToEvent event) {
        Block from = event.getBlock();
        Block to = event.getToBlock();
        if (!recorder.enabled(LogCategory.FLUIDS, to.getWorld())) {
            return;
        }
        Optional<BlockData> flow = FluidFlows.flowing(from.getBlockData(), to.getY() < from.getY(),
                fastLava(to.getWorld()));
        if (flow.isEmpty()) {
            // A dragon egg teleporting (an interaction), or a block that holds no fluid.
            return;
        }
        BlockData flowing = flow.get();
        BlockData replaced = to.getBlockData();
        if (replaced.getMaterial() == flowing.getMaterial()) {
            return;
        }
        Actor fluid = flowing.getMaterial() == Material.LAVA ? Actors.LAVA : Actors.WATER;
        boolean tracking = configProvider.get().logging().liquidTracking();
        Actor actor = tracking ? causes.fluidCause(from).orElse(fluid) : fluid;
        recorder.block(Action.BLOCK_PLACE, actor, to, flowing, replaced, null);
        if (tracking && !actor.equals(fluid)) {
            causes.rememberFluid(to, actor);
        }
    }

    /**
     * Records water and lava forming stone.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onForm(@NotNull BlockFormEvent event) {
        Block block = event.getBlock();
        if (!recorder.enabled(LogCategory.FLUIDS, block.getWorld())) {
            return;
        }
        BlockData before = block.getBlockData();
        BlockData after = event.getNewState().getBlockData();
        if (!BlockChanges.isFluidForm(before.getMaterial(), after.getMaterial())) {
            return;
        }
        Actor other = before.getMaterial() == Material.LAVA ? Actors.WATER : Actors.LAVA;
        Actor actor = configProvider.get().logging().liquidTracking() ? pouredAt(block).orElse(other) : other;
        recorder.block(Action.BLOCK_CHANGE, actor, block, after, before, null);
    }

    /**
     * Records the water a sponge soaks up.
     *
     * @param event the event, raised before the water is removed; its blocks are the new states
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAbsorb(@NotNull SpongeAbsorbEvent event) {
        Block sponge = event.getBlock();
        if (!recorder.enabled(LogCategory.FLUIDS, sponge.getWorld())) {
            return;
        }
        Actor actor = causes.recentCause(sponge).orElse(Actors.SPONGE);
        for (BlockState drained : event.getBlocks()) {
            Block block = drained.getBlock();
            blocks.changed(actor, block, block.getBlockData(), drained.getBlockData());
        }
    }

    /** Who poured the fluid at a block or one that touches it. */
    private @NotNull Optional<Actor> pouredAt(@NotNull Block block) {
        Optional<Actor> own = causes.fluidCause(block);
        if (own.isPresent()) {
            return own;
        }
        for (BlockFace face : TOUCHING) {
            Optional<Actor> touching = causes.fluidCause(block.getRelative(face));
            if (touching.isPresent()) {
                return touching;
            }
        }
        return Optional.empty();
    }

    /**
     * Whether lava flows fast (drops one level per block instead of two), as it does in the nether. The
     * dimension's own flag ({@code World#isUltraWarm}) is deprecated without a replacement.
     */
    private static boolean fastLava(@NotNull World world) {
        return world.getEnvironment() == World.Environment.NETHER;
    }
}
