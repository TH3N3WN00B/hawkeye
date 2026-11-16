package gg.moonrise.hawkeye.listener.entity;

import gg.moonrise.hawkeye.config.file.LogCategory;
import gg.moonrise.hawkeye.core.service.capture.Actors;
import gg.moonrise.hawkeye.core.service.capture.BlockCapture;
import gg.moonrise.hawkeye.core.service.capture.CauseTracker;
import gg.moonrise.hawkeye.core.service.capture.EventRecorder;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.moss.spring.SpringComponent;
import io.papermc.paper.event.entity.EntityConstructEvent;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Golems and withers built from block patterns ({@link LogCategory#ENTITIES}). Placing a pattern's
 * head or body block (carved pumpkin, jack o'lantern, wither skeleton skull, iron or copper block)
 * remembers the player; when the pattern turns into an entity, the builder is whoever is remembered at
 * one of its blocks. Each consumed pattern block is recorded as broken by the builder, the entity as
 * spawned by them, and the entity is tagged ({@link Actors#tag}) so what it does later, a wither's
 * block damage and skulls among it, counts for the builder too.
 */
@SpringComponent
@RequiredArgsConstructor
public final class EntityBuildListener implements Listener {

    /** The blocks whose placement can complete a golem or wither pattern. */
    private static final Set<Material> PATTERN_BLOCKS = EnumSet.of(Material.CARVED_PUMPKIN, Material.JACK_O_LANTERN,
            Material.WITHER_SKELETON_SKULL, Material.WITHER_SKELETON_WALL_SKULL, Material.IRON_BLOCK,
            Material.COPPER_BLOCK, Material.EXPOSED_COPPER, Material.WEATHERED_COPPER, Material.OXIDIZED_COPPER,
            Material.WAXED_COPPER_BLOCK, Material.WAXED_EXPOSED_COPPER, Material.WAXED_WEATHERED_COPPER,
            Material.WAXED_OXIDIZED_COPPER);

    private final @NotNull EventRecorder recorder;
    private final @NotNull BlockCapture blocks;
    private final @NotNull CauseTracker causes;

    /**
     * Remembers who placed a block that can complete a pattern. Paper fires this before the placed
     * block's own placement logic, which builds the entity.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(@NotNull BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        if (event.canBuild() && PATTERN_BLOCKS.contains(block.getType())
                && recorder.enabled(LogCategory.ENTITIES, block.getWorld())) {
            causes.remember(block, Actors.of(event.getPlayer()));
        }
    }

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
