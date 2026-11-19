package gg.moonrise.hawkeye.listener.block;

import gg.moonrise.hawkeye.config.file.LogCategory;
import gg.moonrise.hawkeye.core.service.capture.Actors;
import gg.moonrise.hawkeye.core.service.capture.EventRecorder;
import gg.moonrise.hawkeye.core.service.capture.block.BlockCapture;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.moss.spring.SpringComponent;
import lombok.RequiredArgsConstructor;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.PortalCreateEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Portals forming: a nether portal lit in its frame, the frame and portal built on the other side when
 * an entity travels, and the end platform. Each block the portal changes is classified (usually a place
 * of portal or obsidian, a break of what it cleared) and blamed on the entity that made it, or on
 * {@link Actors#PORTAL}; with a player it is {@link LogCategory#BLOCK_PLACE}, otherwise
 * {@link LogCategory#NATURAL}. The blocks may lie in another world than the one the entity left.
 */
@SpringComponent
@RequiredArgsConstructor
public final class PortalListener implements Listener {

    private final @NotNull EventRecorder recorder;
    private final @NotNull BlockCapture blocks;

    /**
     * Records every block the portal changes, before they change.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCreate(@NotNull PortalCreateEvent event) {
        Entity entity = event.getEntity();
        LogCategory category = entity instanceof Player ? LogCategory.BLOCK_PLACE : LogCategory.NATURAL;
        Actor actor = entity == null ? Actors.PORTAL : Actors.of(entity);
        for (BlockState state : event.getBlocks()) {
            Block block = state.getBlock();
            if (!recorder.enabled(category, block.getWorld())) {
                continue;
            }
            BlockData before = block.getBlockData();
            BlockData after = state.getBlockData();
            // The frame of a lit portal is reported unchanged.
            if (!after.equals(before)) {
                blocks.changed(actor, block, before, after);
            }
        }
    }
}
