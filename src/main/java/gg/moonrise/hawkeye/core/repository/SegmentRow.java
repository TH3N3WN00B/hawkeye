package gg.moonrise.hawkeye.core.repository;

import java.util.Arrays;
import org.jetbrains.annotations.NotNull;

/**
 * One {@code segment} row: the packed events of one world region.
 *
 * @param id      the segment id (0 before it is written)
 * @param world   the world id
 * @param region  the region key ({@code BlockPositions.regionKey})
 * @param firstId the smallest event id inside
 * @param lastId  the largest event id inside
 * @param events  how many events it holds
 * @param body    the packed events ({@link SegmentCodec})
 */
public record SegmentRow(long id, int world, long region, long firstId, long lastId, int events,
        byte @NotNull [] body) {

    @Override
    public boolean equals(Object other) {
        return other instanceof SegmentRow row && id == row.id && world == row.world && region == row.region
                && firstId == row.firstId && lastId == row.lastId && events == row.events
                && Arrays.equals(body, row.body);
    }

    @Override
    public int hashCode() {
        return Long.hashCode(id) * 31 + Arrays.hashCode(body);
    }

    @Override
    public @NotNull String toString() {
        return "SegmentRow[id=" + id + ", world=" + world + ", region=" + region + ", ids=" + firstId + ".." + lastId
                + ", events=" + events + ", " + body.length + " bytes]";
    }
}
