package gg.moonrise.hawkeye.core.service.rollback.plan;

import java.util.List;
import org.jetbrains.annotations.NotNull;

/**
 * The changes of one chunk, in the order they are applied: blocks first (so a restored container,
 * sign or wall exists before its contents, text or painting change), then respawned entities (so an
 * item frame or armour stand is back before its item changes), then container items, then the removal
 * of placed and spawned entities.
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
