package gg.moonrise.hawkeye.core.service.rollback;

import java.util.List;
import org.jetbrains.annotations.NotNull;

/**
 * The changes of one chunk, in the order they are applied: blocks first (so a restored container or
 * sign exists before its contents or text change), then container items, then entities.
 *
 * @param world   the world name
 * @param chunkX  chunk x
 * @param chunkZ  chunk z
 * @param changes the changes
 */
public record ChunkPlan(@NotNull String world, int chunkX, int chunkZ, @NotNull List<PlannedChange> changes) {

    /**
     * Copies the changes.
     *
     * @param world   the world name
     * @param chunkX  chunk x
     * @param chunkZ  chunk z
     * @param changes the changes
     */
    public ChunkPlan {
        changes = List.copyOf(changes);
    }
}
