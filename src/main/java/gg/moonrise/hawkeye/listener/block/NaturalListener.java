package gg.moonrise.hawkeye.listener.block;

import gg.moonrise.hawkeye.config.file.LogCategory;
import gg.moonrise.hawkeye.core.service.capture.Actors;
import gg.moonrise.hawkeye.core.service.capture.BlockCapture;
import gg.moonrise.hawkeye.core.service.capture.BlockChanges;
import gg.moonrise.hawkeye.core.service.capture.EventRecorder;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.moss.spring.SpringComponent;
import lombok.RequiredArgsConstructor;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.EntityBlockFormEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Natural changes ({@link LogCategory#NATURAL}): leaves decaying ({@link Actors#DECAY}), blocks fading
 * (ice and snow melting, coral dying), forming (snow, ice, concrete; a snow golem's trail or frost
 * walker counts for its entity), spreading (grass, mycelium, vines, sculk) and structures growing
 * (trees and huge mushrooms; bone meal counts for its player), each classified as a break, place or
 * change. Fire burning out, fire spreading and water meeting lava belong to other categories.
 */
@SpringComponent
@RequiredArgsConstructor
public final class NaturalListener implements Listener {

    private final @NotNull EventRecorder recorder;
    private final @NotNull BlockCapture blocks;

    /**
     * Records leaves decaying.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDecay(@NotNull LeavesDecayEvent event) {
        if (recorder.enabled(LogCategory.NATURAL, event.getBlock().getWorld())) {
            blocks.broken(Actors.DECAY, event.getBlock());
        }
    }

    /**
     * Records a block fading. Fire burning out is skipped: a rollback must never bring fire back.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFade(@NotNull BlockFadeEvent event) {
        Block block = event.getBlock();
        if (!recorder.enabled(LogCategory.NATURAL, block.getWorld())) {
            return;
        }
        BlockData before = block.getBlockData();
        if (!BlockChanges.isFire(before.getMaterial())) {
            blocks.changed(Actors.NATURE, block, before, event.getNewState().getBlockData());
        }
    }

    /**
     * Records a block forming, including {@link EntityBlockFormEvent}, which shares this handler list.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onForm(@NotNull BlockFormEvent event) {
        Block block = event.getBlock();
        if (!recorder.enabled(LogCategory.NATURAL, block.getWorld())) {
            return;
        }
        BlockData before = block.getBlockData();
        BlockData after = event.getNewState().getBlockData();
        if (BlockChanges.isFluidForm(before.getMaterial(), after.getMaterial())) {
            return;
        }
        Actor actor = event instanceof EntityBlockFormEvent byEntity ? Actors.of(byEntity.getEntity()) : Actors.NATURE;
        blocks.changed(actor, block, before, after);
    }

    /**
     * Records a block spreading; fire spreading is recorded as fire.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpread(@NotNull BlockSpreadEvent event) {
        Block block = event.getBlock();
        if (!recorder.enabled(LogCategory.NATURAL, block.getWorld())) {
            return;
        }
        BlockData after = event.getNewState().getBlockData();
        if (!BlockChanges.isFire(after.getMaterial())) {
            blocks.changed(Actors.NATURE, block, block.getBlockData(), after);
        }
    }

    /**
     * Records every block a growing tree or mushroom changes.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGrow(@NotNull StructureGrowEvent event) {
        if (!recorder.enabled(LogCategory.NATURAL, event.getWorld())) {
            return;
        }
        Player player = event.getPlayer();
        Actor actor = event.isFromBonemeal() && player != null ? Actors.of(player) : Actors.NATURE;
        for (BlockState grown : event.getBlocks()) {
            Block block = grown.getBlock();
            blocks.changed(actor, block, block.getBlockData(), grown.getBlockData());
        }
    }
}
