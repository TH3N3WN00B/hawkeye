package gg.moonrise.hawkeye.listener.block;

import gg.moonrise.hawkeye.config.file.LogCategory;
import gg.moonrise.hawkeye.core.service.capture.Actors;
import gg.moonrise.hawkeye.core.service.capture.EventRecorder;
import gg.moonrise.hawkeye.core.service.capture.block.BlockCapture;
import gg.moonrise.hawkeye.core.service.capture.block.BlockHalves;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.moss.spring.SpringComponent;
import lombok.RequiredArgsConstructor;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Players breaking blocks ({@link LogCategory#BLOCK_BREAK}): the broken state with its container
 * items or sign text, and the other half of a door, tall plant or bed, which physics removes without
 * an event of its own.
 */
@SpringComponent
@RequiredArgsConstructor
public final class BlockBreakListener implements Listener {

    private final @NotNull EventRecorder recorder;
    private final @NotNull BlockCapture blocks;

    /**
     * Records the broken block before the world removes it.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(@NotNull BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!recorder.enabled(LogCategory.BLOCK_BREAK, block.getWorld())) {
            return;
        }
        Actor actor = Actors.of(event.getPlayer());
        blocks.broken(actor, block);
        BlockHalves.otherHalf(block).ifPresent(half -> blocks.broken(actor, half));
    }
}
