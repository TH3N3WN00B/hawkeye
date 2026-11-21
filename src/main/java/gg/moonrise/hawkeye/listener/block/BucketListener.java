package gg.moonrise.hawkeye.listener.block;

import gg.moonrise.hawkeye.config.provider.HawkeyeConfigProvider;
import gg.moonrise.hawkeye.config.file.LogCategory;
import gg.moonrise.hawkeye.core.service.capture.Actors;
import gg.moonrise.hawkeye.core.service.capture.CauseTracker;
import gg.moonrise.hawkeye.core.service.capture.EventRecorder;
import gg.moonrise.hawkeye.core.service.capture.block.BlockCapture;
import gg.moonrise.hawkeye.core.service.capture.block.BlockChanges;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.moss.spring.SpringComponent;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Buckets ({@link LogCategory#BUCKETS}): emptying one places its fluid (or waterlogs the block),
 * filling one removes the fluid (or drains the block). Cauldrons are left to their own event. With
 * {@code liquid-tracking}, poured water and lava remember their player, so the flow counts for them too.
 */
@SpringComponent
@RequiredArgsConstructor
public final class BucketListener implements Listener {

    private final @NotNull EventRecorder recorder;
    private final @NotNull BlockCapture blocks;
    private final @NotNull CauseTracker causes;
    private final @NotNull HawkeyeConfigProvider configProvider;

    /**
     * Records a bucket emptied.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEmpty(@NotNull PlayerBucketEmptyEvent event) {
        Block block = event.getBlock();
        Material fluid = fluid(event.getBucket());
        Actor actor = Actors.of(event.getPlayer());
        if (fluid != Material.POWDER_SNOW && configProvider.get().logging().liquidTracking()) {
            causes.rememberFluid(block, actor);
        }
        if (!recorder.enabled(LogCategory.BUCKETS, block.getWorld())) {
            return;
        }
        BlockData before = block.getBlockData();
        emptied(before, fluid).ifPresent(after -> blocks.changed(actor, block, before, after));
    }

    /**
     * Records a bucket filled.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFill(@NotNull PlayerBucketFillEvent event) {
        Block block = event.getBlock();
        if (!recorder.enabled(LogCategory.BUCKETS, block.getWorld())) {
            return;
        }
        BlockData before = block.getBlockData();
        filled(before).ifPresent(after -> blocks.changed(Actors.of(event.getPlayer()), block, before, after));
    }

    private static @NotNull Material fluid(@NotNull Material bucket) {
        return switch (bucket) {
            case LAVA_BUCKET -> Material.LAVA;
            case POWDER_SNOW_BUCKET -> Material.POWDER_SNOW;
            // Water, and the fish, axolotl and tadpole buckets, which pour water too.
            default -> Material.WATER;
        };
    }

    private static @NotNull Optional<BlockData> emptied(@NotNull BlockData before, @NotNull Material fluid) {
        if (BlockChanges.isEmpty(before.getMaterial())) {
            return Optional.of(fluid.createBlockData());
        }
        if (fluid == Material.WATER && before instanceof Waterlogged waterlogged && !waterlogged.isWaterlogged()) {
            Waterlogged after = (Waterlogged) waterlogged.clone();
            after.setWaterlogged(true);
            return Optional.of(after);
        }
        // A cauldron, or a block the fluid does not go into.
        return Optional.empty();
    }

    private static @NotNull Optional<BlockData> filled(@NotNull BlockData before) {
        if (before instanceof Waterlogged waterlogged && waterlogged.isWaterlogged()) {
            Waterlogged after = (Waterlogged) waterlogged.clone();
            after.setWaterlogged(false);
            return Optional.of(after);
        }
        return switch (before.getMaterial()) {
            case WATER, LAVA, POWDER_SNOW -> Optional.of(Material.AIR.createBlockData());
            default -> Optional.empty();
        };
    }
}
