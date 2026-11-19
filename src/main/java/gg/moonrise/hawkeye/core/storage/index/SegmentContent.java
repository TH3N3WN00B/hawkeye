package gg.moonrise.hawkeye.core.storage.index;

import gg.moonrise.hawkeye.core.storage.index.place.SegmentPlaces;
import org.jetbrains.annotations.NotNull;

/**
 * What one segment contributes to the segment index: the places of its rows and the actors it holds.
 *
 * @param places the places of the segment's rows
 * @param actors the distinct actor ids of its rows, ascending (do not modify)
 */
public record SegmentContent(@NotNull SegmentPlaces places, int @NotNull [] actors) {

    /**
     * The segment id.
     *
     * @return the id
     */
    public long segment() {
        return places.segment();
    }
}
