package gg.moonrise.hawkeye.listener.entity;

import gg.moonrise.hawkeye.config.file.LogCategory;
import gg.moonrise.hawkeye.core.service.capture.Actors;
import gg.moonrise.hawkeye.core.service.capture.EventRecorder;
import gg.moonrise.hawkeye.core.service.capture.block.BlockCapture;
import gg.moonrise.moss.spring.SpringComponent;
import lombok.RequiredArgsConstructor;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Entities changing blocks ({@link LogCategory#ENTITY_CHANGES}): endermen, falling blocks starting and
 * landing, trampled farmland, sheep eating grass, withers, ravagers, silverfish. Each change is
 * classified as a break, place or change and blamed on {@link Actors#of(org.bukkit.entity.Entity)}.
 */
@SpringComponent
@RequiredArgsConstructor
public final class EntityChangeListener implements Listener {

    private final @NotNull EventRecorder recorder;
    private final @NotNull BlockCapture blocks;

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
}
