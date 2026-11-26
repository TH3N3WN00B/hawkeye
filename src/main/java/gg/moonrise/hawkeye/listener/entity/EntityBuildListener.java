package gg.moonrise.hawkeye.listener.entity;

import gg.moonrise.hawkeye.config.file.LogCategory;
import gg.moonrise.hawkeye.core.service.capture.Actors;
import gg.moonrise.hawkeye.core.service.capture.CauseTracker;
import gg.moonrise.hawkeye.core.service.capture.EventRecorder;
import gg.moonrise.moss.spring.SpringComponent;
import java.util.EnumSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Golems and withers built from block patterns ({@link LogCategory#ENTITIES}), first half: placing a
 * pattern's head or body block (carved pumpkin, jack o'lantern, wither skeleton skull, iron or copper
 * block) remembers the player at that block. When the pattern turns into an entity, the builder is whoever
 * is remembered at one of its blocks: {@code EntityConstructListener} records the consumed pattern and the
 * entity (Paper 26.2 and later), {@link BuiltEntitySpawnListener} the entity alone before that.
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
}
