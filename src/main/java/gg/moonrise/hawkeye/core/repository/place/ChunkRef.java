package gg.moonrise.hawkeye.core.repository.place;

import java.util.Comparator;
import org.jetbrains.annotations.NotNull;

/**
 * A chunk of one world, ordered by world, then chunk key.
 *
 * @param world the world id
 * @param chunk the chunk key ({@link PlaceKeys#chunk})
 */
public record ChunkRef(int world, long chunk) implements Comparable<ChunkRef> {

    private static final Comparator<ChunkRef> ORDER = Comparator.comparingInt(ChunkRef::world)
            .thenComparingLong(ChunkRef::chunk);

    /**
     * The region the chunk is in.
     *
     * @return the region
     */
    public @NotNull RegionRef region() {
        return new RegionRef(world, PlaceKeys.region(chunk));
    }

    /**
     * The chunk's number inside its region.
     *
     * @return the number
     */
    public int indexInRegion() {
        return PlaceKeys.indexInRegion(chunk);
    }

    @Override
    public int compareTo(@NotNull ChunkRef other) {
        return ORDER.compare(this, other);
    }
}
