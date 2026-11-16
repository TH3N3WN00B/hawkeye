package gg.moonrise.hawkeye.listener.block;

import gg.moonrise.hawkeye.config.file.LogCategory;
import gg.moonrise.hawkeye.core.service.capture.Actors;
import gg.moonrise.hawkeye.core.service.capture.BlockCapture;
import gg.moonrise.hawkeye.core.service.capture.BlockHalves;
import gg.moonrise.hawkeye.core.service.capture.CauseTracker;
import gg.moonrise.hawkeye.core.service.capture.EventRecorder;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.moss.spring.SpringComponent;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.bukkit.ExplosionResult;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.RespawnAnchor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Blocks destroyed by explosions ({@link LogCategory#EXPLOSIONS}), with their container items. An
 * entity explosion is blamed on {@link Actors#of(org.bukkit.entity.Entity)} (TNT a player lit counts
 * for that player); a block explosion on the player who clicked the bed in the nether or the end, or
 * the charged respawn anchor outside the nether ({@link CauseTracker#recentCause}), else on
 * {@link Actors#EXPLOSION}. Explosions that keep or only trigger blocks (wind charges) change nothing
 * and are skipped.
 */
@SpringComponent
@RequiredArgsConstructor
public final class ExplosionListener implements Listener {

    private final @NotNull EventRecorder recorder;
    private final @NotNull BlockCapture blocks;
    private final @NotNull CauseTracker causes;

    /**
     * Records the blocks an entity explosion destroys.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(@NotNull EntityExplodeEvent event) {
        if (records(event.getEntity().getWorld(), event.getExplosionResult(), event.blockList())) {
            record(Actors.of(event.getEntity()), event.blockList());
        }
    }

    /**
     * Records the blocks a block explosion destroys.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(@NotNull BlockExplodeEvent event) {
        if (!recorder.enabled(LogCategory.EXPLOSIONS, event.getBlock().getWorld())) {
            return;
        }
        Actor actor = causes.recentCause(event.getBlock()).orElse(Actors.EXPLOSION);
        // The bed or anchor itself is gone before the event; Paper keeps its state.
        recordExploded(actor, event.getBlock(), event.getExplodedBlockState().getBlockData());
        if (records(event.getBlock().getWorld(), event.getExplosionResult(), event.blockList())) {
            record(actor, event.blockList());
        }
    }

    /**
     * Records the exploding block itself (a bed's both halves, or the anchor), which the server removes
     * before the event without one of its own.
     */
    private void recordExploded(@NotNull Actor actor, @NotNull Block block, @NotNull BlockData data) {
        if (data.getMaterial().isAir() || causes.recentlyRecorded(block)) {
            return;
        }
        BlockData air = Material.AIR.createBlockData();
        // Hawkeye's Action, fully qualified: Bukkit's click Action is imported in this file.
        recorder.block(gg.moonrise.hawkeye.entity.action.Action.BLOCK_BREAK, actor, block, data, air, null);
        if (data instanceof Bed bed) {
            Bed other = (Bed) bed.clone();
            other.setPart(bed.getPart() == Bed.Part.HEAD ? Bed.Part.FOOT : Bed.Part.HEAD);
            Block half = block.getRelative(bed.getPart() == Bed.Part.HEAD
                    ? bed.getFacing().getOppositeFace() : bed.getFacing());
            if (!causes.recentlyRecorded(half)) {
                recorder.block(gg.moonrise.hawkeye.entity.action.Action.BLOCK_BREAK, actor, half, other, air, null);
            }
        }
    }

    /**
     * Remembers the player who uses a bed or respawn anchor that is about to explode.
     *
     * @param event the event, raised before the block is used
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onUse(@NotNull PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null || event.getAction() != Action.RIGHT_CLICK_BLOCK
                || !recorder.enabled(LogCategory.EXPLOSIONS, block.getWorld()) || !explodes(block)) {
            return;
        }
        Actor actor = Actors.of(event.getPlayer());
        causes.remember(block, actor);
        BlockHalves.otherHalf(block).ifPresent(half -> causes.remember(half, actor));
    }

    private boolean records(@NotNull World world, @NotNull ExplosionResult result, @NotNull List<Block> destroyed) {
        return recorder.enabled(LogCategory.EXPLOSIONS, world) && !destroyed.isEmpty()
                && (result == ExplosionResult.DESTROY || result == ExplosionResult.DESTROY_WITH_DECAY);
    }

    private void record(@NotNull Actor actor, @NotNull List<Block> destroyed) {
        for (Block block : destroyed) {
            blocks.broken(actor, block);
        }
    }

    /** Whether using a block blows it up: a bed in the nether or the end, a charged anchor outside the nether. */
    private static boolean explodes(@NotNull Block block) {
        BlockData data = block.getBlockData();
        World.Environment environment = block.getWorld().getEnvironment();
        return switch (data) {
            case Bed _ -> environment == World.Environment.NETHER || environment == World.Environment.THE_END;
            case RespawnAnchor anchor -> anchor.getCharges() > 0 && environment != World.Environment.NETHER;
            default -> false;
        };
    }
}
