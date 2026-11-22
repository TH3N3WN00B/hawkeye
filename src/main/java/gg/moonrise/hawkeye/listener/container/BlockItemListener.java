package gg.moonrise.hawkeye.listener.container;

import gg.moonrise.hawkeye.config.file.LogCategory;
import gg.moonrise.hawkeye.core.service.capture.Actors;
import gg.moonrise.hawkeye.core.service.capture.EventRecorder;
import gg.moonrise.hawkeye.core.service.capture.block.BlockItems;
import gg.moonrise.hawkeye.core.service.capture.container.InventoryDiff;
import gg.moonrise.hawkeye.core.service.thread.ServerThreads;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.moss.spring.SpringComponent;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Items put into or taken out of blocks that hold them without a menu ({@link LogCategory#CONTAINERS}):
 * jukeboxes, chiseled bookshelves, decorated pots and shelves ({@link BlockItems}). The world applies
 * a right click after the event, so a copy of the contents is compared with the contents on the
 * block's thread a tick later, and the net change per kind of item ({@link InventoryDiff}) is a deposit
 * or withdrawal by the player at the block. One comparison per block and tick: the second hand's
 * click in the same tick is already covered by the first one's copy.
 */
@SpringComponent
@RequiredArgsConstructor
public final class BlockItemListener implements Listener {

    private final @NotNull EventRecorder recorder;
    private final @NotNull ServerThreads threads;
    private final Set<PendingBlock> pending = ConcurrentHashMap.newKeySet();

    /**
     * Copies the contents and compares them next tick.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(@NotNull PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null || event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK
                || event.useInteractedBlock() == Event.Result.DENY || !BlockItems.holdsItems(block.getType())
                || !recorder.enabled(LogCategory.CONTAINERS, block.getWorld())) {
            return;
        }
        Optional<@Nullable ItemStack @NotNull []> contents = BlockItems.contents(block.getState());
        World world = block.getWorld();
        PendingBlock key = new PendingBlock(world.getUID(), block.getX(), block.getY(), block.getZ());
        if (contents.isEmpty() || !pending.add(key)) {
            return;
        }
        ItemStack[] before = contents.get();
        Actor actor = Actors.of(event.getPlayer());
        threads.runAtChunk(world, block.getX() >> 4, block.getZ() >> 4, () -> {
            pending.remove(key);
            BlockItems.contents(block.getState()).ifPresent(after -> record(actor, block, before, after));
        });
    }

    /**
     * The blocks waiting for their comparison.
     *
     * @return the count
     */
    public int pending() {
        return pending.size();
    }

    private void record(@NotNull Actor actor, @NotNull Block block, @Nullable ItemStack @NotNull [] before,
            @Nullable ItemStack @NotNull [] after) {
        recorder.containerChanges(actor, block.getLocation(), InventoryDiff.diff(before, after));
    }

    /**
     * A block with a comparison scheduled.
     *
     * @param world the world's id
     * @param x     block x
     * @param y     block y
     * @param z     block z
     */
    private record PendingBlock(@NotNull UUID world, int x, int y, int z) {
    }
}
