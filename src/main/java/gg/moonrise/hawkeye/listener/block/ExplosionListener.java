package gg.moonrise.hawkeye.listener.block;

import gg.moonrise.hawkeye.config.file.LogCategory;
import gg.moonrise.hawkeye.core.service.capture.Actors;
import gg.moonrise.hawkeye.core.service.capture.BlockCapture;
import gg.moonrise.hawkeye.core.service.capture.EventRecorder;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.moss.spring.SpringComponent;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.bukkit.ExplosionResult;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Blocks destroyed by explosions ({@link LogCategory#EXPLOSIONS}), with their container items. An
 * entity explosion is blamed on {@link Actors#of(org.bukkit.entity.Entity)} (TNT a player lit counts
 * for that player); a block explosion (bed, respawn anchor) on {@link Actors#EXPLOSION}. Explosions
 * that keep or only trigger blocks (wind charges) change nothing and are skipped.
 */
@SpringComponent
@RequiredArgsConstructor
public final class ExplosionListener implements Listener {

    private final @NotNull EventRecorder recorder;
    private final @NotNull BlockCapture blocks;

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
        if (records(event.getBlock().getWorld(), event.getExplosionResult(), event.blockList())) {
            record(Actors.EXPLOSION, event.blockList());
        }
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
}
