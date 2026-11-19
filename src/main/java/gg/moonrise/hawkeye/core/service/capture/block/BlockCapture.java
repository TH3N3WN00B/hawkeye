package gg.moonrise.hawkeye.core.service.capture.block;

import gg.moonrise.hawkeye.core.service.capture.Actors;
import gg.moonrise.hawkeye.core.service.capture.CauseTracker;
import gg.moonrise.hawkeye.core.service.capture.EventRecorder;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.hawkeye.entity.payload.Payload;
import gg.moonrise.moss.spring.SpringComponent;
import lombok.RequiredArgsConstructor;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Block recording shared by the listeners: a removal with the block's contents and what it leaves
 * behind, and a classified change ({@link BlockChanges#classify}). Runs on the event's thread, before
 * the world applies the change, like {@link EventRecorder}. Every recorded removal is also remembered
 * around the block ({@link CauseTracker#rememberAround}), so the torches, signs and plants that fall
 * off because of it are recorded under the same actor.
 */
@SpringComponent
@RequiredArgsConstructor
public final class BlockCapture {

    private final @NotNull EventRecorder recorder;
    private final @NotNull CauseTracker causes;

    /**
     * Records the block's current state as broken: with its container items or sign text, and air (or
     * water, for a waterlogged block) as what remains. Air is ignored.
     *
     * @param actor who or what removes it
     * @param block the block, still in the world
     */
    public void broken(@NotNull Actor actor, @NotNull Block block) {
        BlockData before = block.getBlockData();
        if (before.getMaterial().isAir()) {
            return;
        }
        recorder.record(Action.BLOCK_BREAK, actor, block.getWorld(), block.getX(), block.getY(), block.getZ(),
                before.getAsString(), BlockChanges.remains(before), 0, BlockContents.of(block).orElse(null));
        removed(actor, block);
    }

    /**
     * Records a change classified as a break, place or change; a break keeps the block's contents.
     *
     * @param actor  who or what changes it
     * @param block  the block, still in its old state for a break
     * @param before the state before
     * @param after  the state after
     */
    public void changed(@NotNull Actor actor, @NotNull Block block, @NotNull BlockData before,
            @NotNull BlockData after) {
        BlockChanges.classify(before, after).ifPresent(change -> record(actor, block, change,
                change.action() == Action.BLOCK_BREAK ? BlockContents.of(block).orElse(null) : null));
    }

    /**
     * Records a change the world has already applied (a state read again a tick later), so no contents
     * can be read any more.
     *
     * @param actor  who or what changed it
     * @param block  the block
     * @param change the classified change
     */
    public void applied(@NotNull Actor actor, @NotNull Block block, @NotNull BlockChange change) {
        record(actor, block, change, null);
    }

    private void record(@NotNull Actor actor, @NotNull Block block, @NotNull BlockChange change,
            @Nullable Payload payload) {
        recorder.block(change.action(), actor, block, change.subject(), change.secondary(), payload);
        if (change.action() == Action.BLOCK_BREAK) {
            removed(actor, block);
        }
    }

    private void removed(@NotNull Actor actor, @NotNull Block block) {
        // Remembering "unknown" would only overwrite a better cause: a miss falls back to it anyway.
        if (!actor.equals(Actors.PHYSICS)) {
            causes.rememberAround(block, actor);
        }
    }
}
