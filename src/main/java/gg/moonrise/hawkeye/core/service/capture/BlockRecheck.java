package gg.moonrise.hawkeye.core.service.capture;

import gg.moonrise.hawkeye.core.service.thread.ServerThreads;
import gg.moonrise.moss.spring.SpringComponent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.jetbrains.annotations.NotNull;

/**
 * Reads blocks again a tick later, for changes the server applies after its event without reporting
 * the result (a harvested berry bush, a dispensed bucket, a rail that lost its support). Each chunk's
 * blocks are read in one task on the thread that owns the chunk ({@link ServerThreads#runAtChunk}), so
 * this is Folia-safe and never blocks.
 */
@SpringComponent
@RequiredArgsConstructor
public final class BlockRecheck {

    private final @NotNull ServerThreads threads;

    /**
     * A block and its state when it was seen.
     *
     * @param block  the block
     * @param before its state then
     */
    public record Snapshot(@NotNull Block block, @NotNull BlockData before) {

        /**
         * Takes a snapshot of a block's current state.
         *
         * @param block the block
         * @return the snapshot
         */
        public static @NotNull Snapshot of(@NotNull Block block) {
            return new Snapshot(block, block.getBlockData());
        }
    }

    /** Receives a block that changed since its snapshot. */
    @FunctionalInterface
    public interface Change {

        /**
         * Called on the block's own thread.
         *
         * @param block  the block
         * @param before its state in the snapshot
         * @param after  its state now
         */
        void changed(@NotNull Block block, @NotNull BlockData before, @NotNull BlockData after);
    }

    /**
     * Compares one block with its current state a tick from now.
     *
     * @param block    the block
     * @param before   its state now
     * @param onChange called when the state differs then
     */
    public void afterTick(@NotNull Block block, @NotNull BlockData before, @NotNull Change onChange) {
        afterTick(List.of(new Snapshot(block, before)), onChange);
    }

    /**
     * Compares blocks with their snapshots a tick from now, one task per chunk.
     *
     * @param snapshots the blocks and their states now, all in one world
     * @param onChange  called for each block whose state differs then
     */
    public void afterTick(@NotNull List<Snapshot> snapshots, @NotNull Change onChange) {
        Map<Long, List<Snapshot>> byChunk = new LinkedHashMap<>();
        for (Snapshot snapshot : snapshots) {
            Block block = snapshot.block();
            long chunk = ((long) (block.getX() >> 4) << 32) ^ ((block.getZ() >> 4) & 0xFFFFFFFFL);
            byChunk.computeIfAbsent(chunk, key -> new ArrayList<>()).add(snapshot);
        }
        for (List<Snapshot> chunk : byChunk.values()) {
            Block first = chunk.getFirst().block();
            threads.runAtChunk(first.getWorld(), first.getX() >> 4, first.getZ() >> 4, () -> {
                for (Snapshot snapshot : chunk) {
                    BlockData after = snapshot.block().getBlockData();
                    if (!after.equals(snapshot.before())) {
                        onChange.changed(snapshot.block(), snapshot.before(), after);
                    }
                }
            });
        }
    }
}
