package gg.moonrise.hawkeye.listener.block;

import gg.moonrise.hawkeye.config.file.LogCategory;
import gg.moonrise.hawkeye.core.service.capture.Actors;
import gg.moonrise.hawkeye.core.service.capture.CauseTracker;
import gg.moonrise.hawkeye.core.service.capture.EventRecorder;
import gg.moonrise.hawkeye.core.service.capture.block.BlockCapture;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.moss.spring.SpringComponent;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.TNTPrimeEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.jetbrains.annotations.NotNull;

/**
 * TNT being lit ({@link LogCategory#EXPLOSIONS}): the TNT block is a break by its igniter, the entity
 * that lit it (a player, a flaming arrow's shooter, the TNT that exploded next to it) or else whoever
 * placed it ({@link CauseTracker#takeTnt}), or {@link Actors#TNT}. The igniter is remembered at the
 * block and the primed TNT that appears there is tagged with it ({@link Actors#tag}), so its explosion
 * and the TNT it sets off count for the same player even when redstone or fire lit it.
 */
@SpringComponent
@RequiredArgsConstructor
public final class TntListener implements Listener {

    private final @NotNull EventRecorder recorder;
    private final @NotNull BlockCapture blocks;
    private final @NotNull CauseTracker causes;

    /**
     * Records the TNT block lit, before it turns into primed TNT.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPrime(@NotNull TNTPrimeEvent event) {
        Block block = event.getBlock();
        if (!recorder.enabled(LogCategory.EXPLOSIONS, block.getWorld())) {
            return;
        }
        Entity igniter = event.getPrimingEntity();
        Optional<Actor> placer = causes.takeTnt(block);
        Actor actor = igniter != null ? Actors.of(igniter) : placer.orElse(Actors.TNT);
        // An explosion already recorded the TNT in its block list and a player break its own break; a
        // dispenser primes TNT in front of it where there is no TNT block.
        if (block.getType() == Material.TNT && event.getCause() != TNTPrimeEvent.PrimeCause.EXPLOSION
                && event.getCause() != TNTPrimeEvent.PrimeCause.BLOCK_BREAK) {
            blocks.broken(actor, block);
        }
        causes.remember(block, actor);
    }

    /**
     * Tags primed TNT that no player lit directly with the igniter remembered at its block.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(@NotNull EntitySpawnEvent event) {
        if (!(event.getEntity() instanceof TNTPrimed tnt) || tnt.getSource() instanceof Player
                || !recorder.enabled(LogCategory.EXPLOSIONS, tnt.getWorld())) {
            return;
        }
        causes.recentCause(tnt.getLocation().getBlock()).filter(actor -> !actor.equals(Actors.TNT))
                .ifPresent(actor -> Actors.tag(tnt, actor));
    }
}
