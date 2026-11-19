package gg.moonrise.hawkeye.listener.block;

import gg.moonrise.hawkeye.config.file.LogCategory;
import gg.moonrise.hawkeye.core.service.capture.Actors;
import gg.moonrise.hawkeye.core.service.capture.EventRecorder;
import gg.moonrise.hawkeye.core.service.capture.block.BlockCapture;
import gg.moonrise.hawkeye.core.service.capture.block.BlockChanges;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.moss.spring.SpringComponent;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Lightable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Fire ({@link LogCategory#FIRE}): blocks burnt away by {@link Actors#FIRE}, fire started by a player,
 * lava, lightning or another cause (a place of fire, or a candle or campfire lit in place), and fire
 * spreading. A spreading fire raises both an ignite and a spread event; only the spread is recorded.
 */
@SpringComponent
@RequiredArgsConstructor
public final class FireListener implements Listener {

    private final @NotNull EventRecorder recorder;
    private final @NotNull BlockCapture blocks;

    /**
     * Records a block burnt away.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(@NotNull BlockBurnEvent event) {
        if (recorder.enabled(LogCategory.FIRE, event.getBlock().getWorld())) {
            blocks.broken(Actors.FIRE, event.getBlock());
        }
    }

    /**
     * Records fire started, or a block lit.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onIgnite(@NotNull BlockIgniteEvent event) {
        Block block = event.getBlock();
        if (!recorder.enabled(LogCategory.FIRE, block.getWorld())
                || event.getCause() == BlockIgniteEvent.IgniteCause.SPREAD) {
            return;
        }
        BlockData before = block.getBlockData();
        ignited(block, before).ifPresent(after -> recorder.block(
                BlockChanges.isEmpty(before.getMaterial()) ? Action.BLOCK_PLACE : Action.BLOCK_CHANGE,
                igniter(event), block, after, before, null));
    }

    /**
     * Records fire spreading to a block.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpread(@NotNull BlockSpreadEvent event) {
        Block block = event.getBlock();
        if (!recorder.enabled(LogCategory.FIRE, block.getWorld())) {
            return;
        }
        BlockData after = event.getNewState().getBlockData();
        if (BlockChanges.isFire(after.getMaterial())) {
            recorder.block(Action.BLOCK_PLACE, Actors.FIRE, block, after, block.getBlockData(), null);
        }
    }

    private static @NotNull Optional<BlockData> ignited(@NotNull Block block, @NotNull BlockData before) {
        if (BlockChanges.isEmpty(before.getMaterial())) {
            boolean soul = Tag.SOUL_FIRE_BASE_BLOCKS.isTagged(block.getRelative(BlockFace.DOWN).getType());
            return Optional.of((soul ? Material.SOUL_FIRE : Material.FIRE).createBlockData());
        }
        if (before instanceof Lightable lightable && !lightable.isLit()) {
            Lightable lit = (Lightable) lightable.clone();
            lit.setLit(true);
            return Optional.of(lit);
        }
        return Optional.empty();
    }

    private static @NotNull Actor igniter(@NotNull BlockIgniteEvent event) {
        return switch (event.getCause()) {
            case LAVA -> Actors.LAVA;
            case LIGHTNING -> Actors.LIGHTNING;
            default -> {
                if (event.getIgnitingEntity() != null) {
                    yield Actors.of(event.getIgnitingEntity());
                }
                Block source = event.getIgnitingBlock();
                yield source != null && source.getType() == Material.DISPENSER ? Actors.DISPENSER : Actors.FIRE;
            }
        };
    }
}
