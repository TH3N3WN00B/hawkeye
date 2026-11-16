package gg.moonrise.hawkeye.listener.block;

import gg.moonrise.hawkeye.config.file.LogCategory;
import gg.moonrise.hawkeye.core.service.capture.Actors;
import gg.moonrise.hawkeye.core.service.capture.BlockChanges;
import gg.moonrise.hawkeye.core.service.capture.EventRecorder;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.moss.spring.SpringComponent;
import lombok.RequiredArgsConstructor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Water and lava ({@link LogCategory#FLUIDS}): a fluid flowing into another block is a place of that
 * fluid by {@link Actors#WATER} or {@link Actors#LAVA}, and water meeting lava (cobblestone, stone,
 * obsidian) is a change blamed on the fluid that was not replaced. Flow within the same fluid only
 * changes its level and is not recorded.
 */
@SpringComponent
@RequiredArgsConstructor
public final class FluidListener implements Listener {

    private final @NotNull EventRecorder recorder;

    /**
     * Records a fluid flowing into a block.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFlow(@NotNull BlockFromToEvent event) {
        Block to = event.getToBlock();
        if (!recorder.enabled(LogCategory.FLUIDS, to.getWorld())) {
            return;
        }
        BlockData from = event.getBlock().getBlockData();
        BlockData flowing = switch (from.getMaterial()) {
            case WATER, LAVA -> from;
            default -> from instanceof Waterlogged waterlogged && waterlogged.isWaterlogged()
                    ? Material.WATER.createBlockData() : null;
        };
        if (flowing == null) {
            // A dragon egg teleporting, or a block that holds no fluid.
            return;
        }
        BlockData replaced = to.getBlockData();
        if (replaced.getMaterial() != flowing.getMaterial()) {
            recorder.block(Action.BLOCK_PLACE, flowing.getMaterial() == Material.LAVA ? Actors.LAVA : Actors.WATER,
                    to, flowing, replaced, null);
        }
    }

    /**
     * Records water and lava forming stone.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onForm(@NotNull BlockFormEvent event) {
        Block block = event.getBlock();
        if (!recorder.enabled(LogCategory.FLUIDS, block.getWorld())) {
            return;
        }
        BlockData before = block.getBlockData();
        BlockData after = event.getNewState().getBlockData();
        if (BlockChanges.isFluidForm(before.getMaterial(), after.getMaterial())) {
            recorder.block(Action.BLOCK_CHANGE, before.getMaterial() == Material.LAVA ? Actors.WATER : Actors.LAVA,
                    block, after, before, null);
        }
    }
}
