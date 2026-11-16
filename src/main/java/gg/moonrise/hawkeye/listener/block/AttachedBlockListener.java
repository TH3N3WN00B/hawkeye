package gg.moonrise.hawkeye.listener.block;

import com.destroystokyo.paper.event.block.BlockDestroyEvent;
import gg.moonrise.hawkeye.config.HawkeyeConfigProvider;
import gg.moonrise.hawkeye.config.file.LogCategory;
import gg.moonrise.hawkeye.core.service.capture.Actors;
import gg.moonrise.hawkeye.core.service.capture.BlockCapture;
import gg.moonrise.hawkeye.core.service.capture.BlockChanges;
import gg.moonrise.hawkeye.core.service.capture.BlockRecheck;
import gg.moonrise.hawkeye.core.service.capture.CauseTracker;
import gg.moonrise.hawkeye.core.service.capture.EventRecorder;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.moss.spring.SpringComponent;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Blocks that fall off when their support goes ({@code logging.attached-blocks}): torches, signs,
 * flowers, carpets, doors, ladders, buttons, rails, redstone, sugar cane and cactus columns. The server
 * reports most of them as a destroyed block, recorded under whoever recently removed the support
 * ({@link CauseTracker#recentCause}, {@link LogCategory#BLOCK_BREAK}) or else {@link Actors#PHYSICS}
 * ({@link LogCategory#NATURAL}), with their contents, and remembered in turn so a whole column counts
 * for the same actor. Rails and redstone are removed without an event, so after a player's break the
 * six neighbours are read again a tick later and one that vanished counts for that player. A change
 * another listener already recorded (a piston's destroyed block, an entity's change) is skipped, and
 * fire is never recorded: a rollback must not bring it back.
 */
@SpringComponent
@RequiredArgsConstructor
public final class AttachedBlockListener implements Listener {

    private static final BlockFace[] NEIGHBOURS = {BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH,
            BlockFace.EAST, BlockFace.WEST};

    private final @NotNull EventRecorder recorder;
    private final @NotNull BlockCapture blocks;
    private final @NotNull BlockRecheck recheck;
    private final @NotNull CauseTracker causes;
    private final @NotNull HawkeyeConfigProvider configProvider;

    /**
     * Records a block the server destroys, before it is removed. Player breaks never raise this event.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDestroy(@NotNull BlockDestroyEvent event) {
        Block block = event.getBlock();
        if (!configProvider.get().logging().attachedBlocks() || causes.recentlyRecorded(block)) {
            return;
        }
        BlockData before = block.getBlockData();
        BlockData after = event.getNewState();
        if (before.equals(after) || BlockChanges.isFire(before.getMaterial())) {
            return;
        }
        Actor actor = causes.recentCause(block).orElse(Actors.PHYSICS);
        LogCategory category = actor.equals(Actors.PHYSICS) ? LogCategory.NATURAL : LogCategory.BLOCK_BREAK;
        if (recorder.enabled(category, block.getWorld())) {
            // A break remembers the actor around the block, so the next block of a column inherits it.
            blocks.changed(actor, block, before, after);
        }
    }

    /**
     * Snapshots the neighbours of a block a player breaks and records, a tick later, the ones that
     * vanished without an event of their own.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(@NotNull BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!configProvider.get().logging().attachedBlocks()
                || !recorder.enabled(LogCategory.BLOCK_BREAK, block.getWorld())) {
            return;
        }
        List<BlockRecheck.Snapshot> neighbours = new ArrayList<>(NEIGHBOURS.length);
        for (BlockFace face : NEIGHBOURS) {
            BlockRecheck.Snapshot neighbour = BlockRecheck.Snapshot.of(block.getRelative(face));
            if (watched(neighbour.before().getMaterial())) {
                neighbours.add(neighbour);
            }
        }
        if (neighbours.isEmpty()) {
            return;
        }
        Actor actor = Actors.of(event.getPlayer());
        recheck.afterTick(neighbours, (neighbour, before, after) -> {
            if (!causes.recentlyRecorded(neighbour)) {
                BlockChanges.classify(before, after).filter(change -> change.action() == Action.BLOCK_BREAK)
                        .ifPresent(change -> blocks.applied(actor, neighbour, change));
            }
        });
    }

    /** Whether a neighbour can fall off: not air, and not a fluid or fire, which change on their own. */
    private static boolean watched(@NotNull Material material) {
        return !material.isAir() && !BlockChanges.isFire(material) && material != Material.WATER
                && material != Material.LAVA && material != Material.BUBBLE_COLUMN;
    }
}
