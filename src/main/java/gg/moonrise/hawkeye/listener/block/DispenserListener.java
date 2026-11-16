package gg.moonrise.hawkeye.listener.block;

import gg.moonrise.hawkeye.config.HawkeyeConfigProvider;
import gg.moonrise.hawkeye.config.file.LogCategory;
import gg.moonrise.hawkeye.core.service.capture.Actors;
import gg.moonrise.hawkeye.core.service.capture.BlockCapture;
import gg.moonrise.hawkeye.core.service.capture.BlockChanges;
import gg.moonrise.hawkeye.core.service.capture.BlockRecheck;
import gg.moonrise.hawkeye.core.service.capture.CauseTracker;
import gg.moonrise.hawkeye.core.service.capture.EventRecorder;
import gg.moonrise.moss.spring.SpringComponent;
import lombok.RequiredArgsConstructor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Directional;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDispenseEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Dispensers changing the block in front of them ({@link LogCategory#DISPENSERS}): a bucket poured or
 * filled, a shulker box or carved pumpkin placed, a block watered into mud. The dispenser acts after its
 * event, so the block is read again on its own thread a tick later and the change classified as a
 * place, break or change by {@link Actors#DISPENSER}. A change another listener already recorded (bone
 * meal, fire) is not recorded twice. With {@code liquid-tracking}, a poured liquid's flow counts for
 * the dispenser too. Droppers only move items and are skipped.
 */
@SpringComponent
@RequiredArgsConstructor
public final class DispenserListener implements Listener {

    private final @NotNull EventRecorder recorder;
    private final @NotNull BlockCapture blocks;
    private final @NotNull BlockRecheck recheck;
    private final @NotNull CauseTracker causes;
    private final @NotNull HawkeyeConfigProvider configProvider;

    /**
     * Remembers the facing block and compares it next tick.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDispense(@NotNull BlockDispenseEvent event) {
        Block dispenser = event.getBlock();
        if (dispenser.getType() != Material.DISPENSER || !(dispenser.getBlockData() instanceof Directional facing)
                || !recorder.enabled(LogCategory.DISPENSERS, dispenser.getWorld())) {
            return;
        }
        Block target = dispenser.getRelative(facing.getFacing());
        if (pours(event.getItem().getType()) && configProvider.get().logging().liquidTracking()) {
            causes.rememberFluid(target, Actors.DISPENSER);
        }
        recheck.afterTick(target, target.getBlockData(), (block, before, after) -> {
            if (!causes.recentlyRecorded(block)) {
                BlockChanges.classify(before, after)
                        .ifPresent(change -> blocks.applied(Actors.DISPENSER, block, change));
            }
        });
    }

    private static boolean pours(@NotNull Material item) {
        return switch (item) {
            case WATER_BUCKET, LAVA_BUCKET, COD_BUCKET, SALMON_BUCKET, PUFFERFISH_BUCKET, TROPICAL_FISH_BUCKET,
                    AXOLOTL_BUCKET, TADPOLE_BUCKET -> true;
            default -> false;
        };
    }
}
