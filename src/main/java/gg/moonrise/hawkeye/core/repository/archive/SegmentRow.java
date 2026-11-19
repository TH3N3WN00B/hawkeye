package gg.moonrise.hawkeye.core.repository.archive;

import java.util.Arrays;
import org.jetbrains.annotations.NotNull;

/**
 * A candidate {@code segment} row as the archive reads it: everything but the body, which is fetched
 * by byte range ({@link SegmentRepository#readRanges}).
 *
 * @param id         the segment id
 * @param firstId    the smallest event id inside
 * @param lastId     the largest event id inside
 * @param events     how many events it holds
 * @param header     the uncompressed header ({@code segment.SegmentHeader})
 * @param rolledBack ascending ordinals of its rolled-back rows, from {@code segment_flag}
 */
public record SegmentRow(long id, long firstId, long lastId, int events, byte @NotNull [] header,
        int @NotNull [] rolledBack) {

    @Override
    public boolean equals(Object other) {
        return other instanceof SegmentRow row && id == row.id && firstId == row.firstId && lastId == row.lastId
                && events == row.events && Arrays.equals(header, row.header)
                && Arrays.equals(rolledBack, row.rolledBack);
    }

    @Override
    public int hashCode() {
        return Long.hashCode(id) * 31 + Arrays.hashCode(header);
    }

    @Override
    public @NotNull String toString() {
        return "SegmentRow[id=" + id + ", ids=" + firstId + ".." + lastId + ", events=" + events + ", header="
                + header.length + " bytes, rolledBack=" + rolledBack.length + "]";
    }
}
