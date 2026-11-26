package gg.moonrise.hawkeye.listener.entity;

import gg.moonrise.hawkeye.config.file.LogCategory;
import gg.moonrise.hawkeye.core.service.capture.Actors;
import gg.moonrise.hawkeye.core.service.capture.CauseTracker;
import gg.moonrise.hawkeye.core.service.capture.EventRecorder;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.hawkeye.util.compat.ConditionalOnMissingApi;
import gg.moonrise.hawkeye.util.compat.NewerApi;
import gg.moonrise.moss.spring.SpringComponent;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.jetbrains.annotations.NotNull;

/**
 * Golems and withers built from block patterns ({@link LogCategory#ENTITIES}) on servers before Paper 26.2,
 * which lack the construct event ({@code EntityConstructListener} handles it there). The entity's
 * {@link CreatureSpawnEvent} names neither the builder nor the pattern, but comes while the pattern is still
 * in the world, and no pattern block is more than one block beside or two above the block the entity
 * stands in. The builder is whoever {@link EntityBuildListener} remembers at one of those blocks; the entity
 * is recorded as spawned by them and tagged with them, as on newer servers. The consumed pattern blocks are
 * not recorded: a rollback removes the entity and leaves the pattern's place empty.
 */
@SpringComponent
@ConditionalOnMissingApi(NewerApi.ENTITY_CONSTRUCT_EVENT)
@RequiredArgsConstructor
public final class BuiltEntitySpawnListener implements Listener {

    /** The build reasons this server knows ({@code BUILD_COPPERGOLEM} only from Minecraft 1.21.9). */
    private static final Set<SpawnReason> BUILT = Arrays.stream(SpawnReason.values())
            .filter(reason -> reason.name().startsWith("BUILD_"))
            .collect(Collectors.toCollection(() -> EnumSet.noneOf(SpawnReason.class)));

    private final @NotNull EventRecorder recorder;
    private final @NotNull CauseTracker causes;

    /**
     * Records a built entity as spawned by its builder, else by its own type, and tags it with the builder.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(@NotNull CreatureSpawnEvent event) {
        Entity entity = event.getEntity();
        if (!BUILT.contains(event.getSpawnReason()) || !recorder.enabled(LogCategory.ENTITIES, entity.getWorld())) {
            return;
        }
        Optional<Actor> builder = builder(entity.getLocation().getBlock());
        recorder.entitySpawn(builder.orElseGet(() -> Actors.of(entity)), entity);
        builder.ifPresent(known -> Actors.tag(entity, known));
    }

    private @NotNull Optional<Actor> builder(@NotNull Block base) {
        for (int dy = 0; dy <= 2; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    Optional<Actor> cause = causes.recentCause(base.getRelative(dx, dy, dz));
                    if (cause.isPresent()) {
                        return cause;
                    }
                }
            }
        }
        return Optional.empty();
    }
}
