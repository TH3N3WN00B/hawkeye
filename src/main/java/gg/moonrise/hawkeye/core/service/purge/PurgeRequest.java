package gg.moonrise.hawkeye.core.service.purge;

import java.time.Duration;
import java.util.Set;
import org.jetbrains.annotations.NotNull;

/**
 * A purge to run on the home shard.
 *
 * @param olderThan events older than this are deleted
 * @param worlds    world names of this server to purge, or empty for every world in the shard
 * @param limited   whether {@code purge.player-minimum-days} applies (players; not the console or
 *                  the automatic purge)
 */
public record PurgeRequest(@NotNull Duration olderThan, @NotNull Set<String> worlds, boolean limited) {

    /**
     * Copies the worlds.
     *
     * @param olderThan the age
     * @param worlds    world names, or empty for all
     * @param limited   whether the player minimum applies
     */
    public PurgeRequest {
        worlds = Set.copyOf(worlds);
    }
}
