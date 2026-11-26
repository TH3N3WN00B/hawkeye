package gg.moonrise.hawkeye.listener.entity;

import gg.moonrise.hawkeye.config.file.LogCategory;
import gg.moonrise.hawkeye.core.service.capture.Actors;
import gg.moonrise.hawkeye.core.service.capture.CauseTracker;
import gg.moonrise.hawkeye.core.service.capture.EventRecorder;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.moss.spring.SpringComponent;
import lombok.RequiredArgsConstructor;
import org.bukkit.block.Block;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.LeashHitch;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerBucketEntityEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Non-living entities destroyed or taken ({@link LogCategory#ENTITIES}), recorded as
 * {@link gg.moonrise.hawkeye.entity.action.Action#ENTITY_REMOVE} with the entity's bytes so a rollback
 * puts it back: paintings and item frames (by whatever broke them; a frame that fell off because its
 * wall went is blamed on whoever removed the wall, else {@link Actors#PHYSICS}), boats and minecarts
 * (the attacker, else {@link VehicleDamage}), mobs caught in a bucket (the player) and end
 * crystals hit by an entity. A hit crystal is also tagged with the hitter ({@link LogCategory#EXPLOSIONS}),
 * so the blocks its explosion destroys count for them. Other placeable entities such as cushions are
 * recorded by {@code EntityBreakListener} (Paper 26.3 and later).
 */
@SpringComponent
@RequiredArgsConstructor
public final class EntityRemoveListener implements Listener {

    /** A hanging or cushion broken by damage without an entity or explosion behind it. */
    public static final Actor UNKNOWN = Actor.source("unknown");

    private final @NotNull EventRecorder recorder;
    private final @NotNull CauseTracker causes;
    private final @NotNull VehicleDamage vehicleDamage;

    /**
     * Records a painting or item frame breaking. {@link HangingBreakByEntityEvent} shares this event's
     * handler list, so breaks by an entity arrive here too.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHangingBreak(@NotNull HangingBreakEvent event) {
        Hanging hanging = event.getEntity();
        if (hanging instanceof LeashHitch || !recorder.enabled(LogCategory.ENTITIES, hanging.getWorld())) {
            return;
        }
        recorder.entityRemove(remover(event), hanging);
    }

    /**
     * Records a boat or minecart destroyed.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVehicleDestroy(@NotNull VehicleDestroyEvent event) {
        Vehicle vehicle = event.getVehicle();
        if (!recorder.enabled(LogCategory.ENTITIES, vehicle.getWorld())) {
            return;
        }
        Entity attacker = event.getAttacker();
        recorder.entityRemove(attacker != null ? Actors.of(attacker) : vehicleDamage.cause(event), vehicle);
    }

    /**
     * Records a fish, axolotl or tadpole caught in a bucket.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketEntity(@NotNull PlayerBucketEntityEvent event) {
        Entity entity = event.getEntity();
        if (recorder.enabled(LogCategory.ENTITIES, entity.getWorld())) {
            recorder.entityRemove(Actors.of(event.getPlayer()), entity);
        }
    }

    /**
     * Records an end crystal hit by an entity (any hit destroys it) and tags it with the hitter.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCrystalHit(@NotNull EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof EnderCrystal crystal)) {
            return;
        }
        Actor hitter = Actors.of(event.getDamager());
        if (recorder.enabled(LogCategory.EXPLOSIONS, crystal.getWorld())) {
            Actors.tag(crystal, hitter);
        }
        if (recorder.enabled(LogCategory.ENTITIES, crystal.getWorld())) {
            recorder.entityRemove(hitter, crystal);
        }
    }

    private @NotNull Actor remover(@NotNull HangingBreakEvent event) {
        if (event instanceof HangingBreakByEntityEvent byEntity) {
            return Actors.of(byEntity.getRemover());
        }
        Hanging hanging = event.getEntity();
        Block own = hanging.getLocation().getBlock();
        return switch (event.getCause()) {
            case PHYSICS, OBSTRUCTION -> causes.recentCause(own.getRelative(hanging.getAttachedFace()))
                    .or(() -> causes.recentCause(own))
                    .orElse(Actors.PHYSICS);
            case EXPLOSION -> Actors.EXPLOSION;
            case ENTITY, DEFAULT -> UNKNOWN;
        };
    }
}
