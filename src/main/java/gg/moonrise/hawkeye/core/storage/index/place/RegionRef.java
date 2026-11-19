package gg.moonrise.hawkeye.core.storage.index.place;

import java.util.Comparator;
import org.jetbrains.annotations.NotNull;

/**
 * A region (32 x 32 chunks) of one world, ordered by world, then region key.
 *
 * @param world  the world id
 * @param region the region key ({@link PlaceKeys#region})
 */
public record RegionRef(int world, long region) implements Comparable<RegionRef> {

    private static final Comparator<RegionRef> ORDER = Comparator.comparingInt(RegionRef::world)
            .thenComparingLong(RegionRef::region);

    /**
     * A chunk of this region.
     *
     * @param index the chunk's number inside the region
     * @return the chunk
     */
    public @NotNull ChunkRef chunk(int index) {
        return new ChunkRef(world, PlaceKeys.chunkOf(region, index));
    }

    @Override
    public int compareTo(@NotNull RegionRef other) {
        return ORDER.compare(this, other);
    }
}
