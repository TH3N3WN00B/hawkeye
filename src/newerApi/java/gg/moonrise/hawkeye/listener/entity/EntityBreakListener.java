package gg.moonrise.hawkeye.listener.entity;

import gg.moonrise.hawkeye.config.file.LogCategory;
import gg.moonrise.hawkeye.core.service.capture.Actors;
import gg.moonrise.hawkeye.core.service.capture.CauseTracker;
import gg.moonrise.hawkeye.core.service.capture.EventRecorder;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.hawkeye.util.compat.ConditionalOnApi;
import gg.moonrise.hawkeye.util.compat.NewerApi;
import gg.moonrise.moss.spring.SpringComponent;
import io.papermc.paper.event.entity.EntityBreakByEntityEvent;
import io.papermc.paper.event.entity.EntityBreakEvent;
import lombok.RequiredArgsConstructor;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Placeable entities other than paintings, item frames and leash knots destroyed ({@link LogCategory#ENTITIES}),
 * on Paper 26.3 and later, which added them (cushions) and their break event: recorded like
 * {@link EntityRemoveListener}'s paintings, by whatever broke them.
 */
@SpringComponent
@ConditionalOnApi(NewerApi.ENTITY_BREAK_EVENT)
@RequiredArgsConstructor
public final class EntityBreakListener implements Listener {

    private final @NotNull EventRecorder recorder;
    private final @NotNull CauseTracker causes;

    /**
     * Records a placeable entity other than a painting, item frame or leash knot breaking (a cushion).
     * {@link EntityBreakByEntityEvent} shares this event's handler list. Paper fires this event only
     * for block-attached entities (never for vehicles), and for a hanging one right after its
     * {@link HangingBreakEvent} (recorded by {@link EntityRemoveListener}), so hanging entities are skipped.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityBreak(@NotNull EntityBreakEvent event) {
        Entity entity = event.getEntity();
        if (entity instanceof Hanging || !recorder.enabled(LogCategory.ENTITIES, entity.getWorld())) {
            return;
        }
        recorder.entityRemove(remover(event), entity);
    }

    private @NotNull Actor remover(@NotNull EntityBreakEvent event) {
        if (event instanceof EntityBreakByEntityEvent byEntity) {
            Entity causing = byEntity.getDamageSource().getCausingEntity();
            return Actors.of(causing != null ? causing : byEntity.getRemover());
        }
        Block own = event.getEntity().getLocation().getBlock();
        return switch (event.getCause()) {
            case PHYSICS, OBSTRUCTION -> causes.recentCause(own)
                    .or(() -> causes.recentCause(own.getRelative(BlockFace.DOWN)))
                    .orElse(Actors.PHYSICS);
            case EXPLOSION -> Actors.EXPLOSION;
            case ENTITY, DEFAULT -> EntityRemoveListener.UNKNOWN;
        };
    }
}
