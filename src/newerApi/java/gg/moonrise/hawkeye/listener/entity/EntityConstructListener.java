package gg.moonrise.hawkeye.listener.entity;

import gg.moonrise.hawkeye.config.file.LogCategory;
import gg.moonrise.hawkeye.core.service.capture.Actors;
import gg.moonrise.hawkeye.core.service.capture.CauseTracker;
import gg.moonrise.hawkeye.core.service.capture.EventRecorder;
import gg.moonrise.hawkeye.core.service.capture.block.BlockCapture;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.hawkeye.util.compat.ConditionalOnApi;
import gg.moonrise.hawkeye.util.compat.NewerApi;
import gg.moonrise.moss.spring.SpringComponent;
import io.papermc.paper.event.entity.EntityConstructEvent;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.NotNull;

/**
 * Golems and withers built from block patterns ({@link LogCategory#ENTITIES}), on Paper 26.2 and later:
 * the builder is whoever {@link EntityBuildListener} remembers at one of the pattern's blocks. Each consumed
 * pattern block is recorded as broken by the builder, the entity as spawned by them, and the entity is
 * tagged ({@link Actors#tag}) so what it does later, a wither's block damage and skulls among it, counts
 * for the builder too.
 */
@SpringComponent
@ConditionalOnApi(NewerApi.ENTITY_CONSTRUCT_EVENT)
@RequiredArgsConstructor
public final class EntityConstructListener implements Listener {

    private final @NotNull EventRecorder recorder;
    private final @NotNull BlockCapture blocks;
    private final @NotNull CauseTracker causes;

    /**
     * Records the consumed pattern and the entity, and tags the entity with its builder. The event comes
     * just before the entity is added and the pattern cleared.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConstruct(@NotNull EntityConstructEvent event) {
        Entity entity = event.getEntity();
        if (!recorder.enabled(LogCategory.ENTITIES, entity.getWorld())) {
            return;
        }
        List<Block> pattern = event.getBlocks();
        Optional<Actor> builder = builder(pattern);
        Actor actor = builder.orElseGet(() -> Actors.of(entity));
        for (Block block : pattern) {
            blocks.broken(actor, block);
        }
        recorder.entitySpawn(actor, entity);
        builder.ifPresent(known -> Actors.tag(entity, known));
    }

    private @NotNull Optional<Actor> builder(@NotNull List<Block> pattern) {
        for (Block block : pattern) {
            Optional<Actor> cause = causes.recentCause(block);
            if (cause.isPresent()) {
                return cause;
            }
        }
        return Optional.empty();
    }
}
