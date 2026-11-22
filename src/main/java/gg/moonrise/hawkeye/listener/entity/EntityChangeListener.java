package gg.moonrise.hawkeye.listener.entity;

import gg.moonrise.hawkeye.config.file.LogCategory;
import gg.moonrise.hawkeye.core.service.capture.Actors;
import gg.moonrise.hawkeye.core.service.capture.CauseTracker;
import gg.moonrise.hawkeye.core.service.capture.EventRecorder;
import gg.moonrise.hawkeye.core.service.capture.block.BlockCapture;
import gg.moonrise.hawkeye.core.service.capture.block.BlockChanges;
import gg.moonrise.hawkeye.core.service.capture.block.BlockRecheck;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.moss.spring.SpringComponent;
import lombok.RequiredArgsConstructor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Entities changing blocks ({@link LogCategory#ENTITY_CHANGES}): endermen, falling blocks starting and
 * landing, trampled farmland, sheep eating grass, withers, ravagers, silverfish, and turtle eggs cracked
 * by a player or mob walking or landing on them. Each change is classified as a break, place or change
 * and blamed on {@link Actors#of(org.bukkit.entity.Entity)}.
 */
@SpringComponent
@RequiredArgsConstructor
public final class EntityChangeListener implements Listener {

    private final @NotNull EventRecorder recorder;
    private final @NotNull BlockCapture blocks;
    private final @NotNull BlockRecheck recheck;
    private final @NotNull CauseTracker causes;

    /**
     * Records the change before the world applies it.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChange(@NotNull EntityChangeBlockEvent event) {
        Block block = event.getBlock();
        if (recorder.enabled(LogCategory.ENTITY_CHANGES, block.getWorld())) {
            blocks.changed(Actors.of(event.getEntity()), block, block.getBlockData(), event.getBlockData());
        }
    }

    /**
     * Records a turtle egg a player cracks by stepping or landing on it. Paper raises this physical
     * interaction only once the egg is about to crack, and no block change event follows.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerStep(@NotNull PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (event.getAction() == Action.PHYSICAL && block != null) {
            crack(Actors.of(event.getPlayer()), block);
        }
    }

    /**
     * Records a turtle egg a mob cracks by stepping or landing on it.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityStep(@NotNull EntityInteractEvent event) {
        crack(Actors.of(event.getEntity()), event.getBlock());
    }

    /**
     * Reads a turtle egg again a tick later: one egg fewer is a change, the last one gone a break. The
     * last egg goes through the server's block destroy, whose event the attached-block listener records
     * under the remembered actor; this recheck then skips the block.
     */
    private void crack(@NotNull Actor actor, @NotNull Block block) {
        if (block.getType() != Material.TURTLE_EGG || !recorder.enabled(LogCategory.ENTITY_CHANGES, block.getWorld())) {
            return;
        }
        causes.remember(block, actor);
        recheck.afterTick(block, block.getBlockData(), (egg, before, after) -> {
            if (!causes.recentlyRecorded(egg)) {
                BlockChanges.classify(before, after).ifPresent(change -> blocks.applied(actor, egg, change));
            }
        });
    }
}
