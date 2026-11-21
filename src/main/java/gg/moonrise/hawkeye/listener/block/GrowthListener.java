package gg.moonrise.hawkeye.listener.block;

import gg.moonrise.hawkeye.config.provider.HawkeyeConfigProvider;
import gg.moonrise.hawkeye.config.file.LogCategory;
import gg.moonrise.hawkeye.core.service.capture.Actors;
import gg.moonrise.hawkeye.core.service.capture.CauseTracker;
import gg.moonrise.hawkeye.core.service.capture.EventRecorder;
import gg.moonrise.hawkeye.core.service.capture.block.BlockCapture;
import gg.moonrise.hawkeye.core.service.capture.block.CropGrowth;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.moss.spring.SpringComponent;
import lombok.RequiredArgsConstructor;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Plants growing and bone meal. Growth ({@link LogCategory#CROP_GROWTH}) is recorded compactly as
 * {@link Action#BLOCK_GROW} by {@link Actors#GROWTH} with no secondary: a new block (melon, pumpkin,
 * sugar cane, cactus, vine, amethyst bud) or a ripe crop, every stage only with
 * {@code crop-growth-stages}; rollbacks never change it. Bone meal is a place or change of every block
 * it grows, by its player ({@link LogCategory#BLOCK_PLACE}) or {@link Actors#DISPENSER}
 * ({@link LogCategory#DISPENSERS}). Bamboo, kelp and twisting vines grow by spreading, which the natural
 * listener records.
 */
@SpringComponent
@RequiredArgsConstructor
public final class GrowthListener implements Listener {

    private final @NotNull EventRecorder recorder;
    private final @NotNull BlockCapture blocks;
    private final @NotNull CauseTracker causes;
    private final @NotNull HawkeyeConfigProvider configProvider;

    /**
     * Records a plant growing. {@link org.bukkit.event.block.BlockFormEvent} and
     * {@link org.bukkit.event.block.BlockSpreadEvent} have their own handler lists and never arrive here.
     *
     * @param event the event, raised before the block grows
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGrow(@NotNull BlockGrowEvent event) {
        Block block = event.getBlock();
        if (!recorder.enabled(LogCategory.CROP_GROWTH, block.getWorld())) {
            return;
        }
        BlockData grown = event.getNewState().getBlockData();
        if (CropGrowth.recorded(block.getBlockData(), grown, configProvider.get().logging().cropGrowthStages())) {
            recorder.record(Action.BLOCK_GROW, Actors.GROWTH, block.getWorld(), block.getX(), block.getY(),
                    block.getZ(), grown.getAsString(), null, 0, null);
        }
    }

    /**
     * Records the blocks bone meal grows. A sapling or fungus growing into a tree also raises a structure
     * growth, which the natural listener records first; those blocks are not recorded twice.
     *
     * @param event the event, raised before the blocks change
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFertilize(@NotNull BlockFertilizeEvent event) {
        Player player = event.getPlayer();
        if (!recorder.enabled(player == null ? LogCategory.DISPENSERS : LogCategory.BLOCK_PLACE,
                event.getBlock().getWorld())) {
            return;
        }
        Actor actor = player == null ? Actors.DISPENSER : Actors.of(player);
        boolean structure = growsStructure(event.getBlock().getType());
        for (BlockState grown : event.getBlocks()) {
            Block block = grown.getBlock();
            if (!structure || !causes.recentlyRecorded(block)) {
                blocks.changed(actor, block, block.getBlockData(), grown.getBlockData());
            }
        }
    }

    private static boolean growsStructure(@NotNull Material material) {
        return switch (material) {
            case BROWN_MUSHROOM, RED_MUSHROOM, CRIMSON_FUNGUS, WARPED_FUNGUS -> true;
            default -> Tag.SAPLINGS.isTagged(material);
        };
    }
}
