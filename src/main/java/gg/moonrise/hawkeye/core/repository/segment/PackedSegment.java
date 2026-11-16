package gg.moonrise.hawkeye.core.repository.segment;

import java.util.Arrays;
import org.jetbrains.annotations.NotNull;

/**
 * An encoded segment ({@link Segments#encode}).
 *
 * @param firstId    the smallest event id inside
 * @param lastId     the largest event id inside
 * @param events     how many events it holds
 * @param header     the uncompressed header ({@link SegmentHeader})
 * @param body       the frames
 * @param rolledBack ascending ordinals of the rows whose input had {@code EventRow.ROLLED_BACK} set
 */
public record PackedSegment(long firstId, long lastId, int events, byte @NotNull [] header, byte @NotNull [] body,
        int @NotNull [] rolledBack) {

    @Override
    public boolean equals(Object other) {
        return other instanceof PackedSegment segment && firstId == segment.firstId && lastId == segment.lastId
                && events == segment.events && Arrays.equals(header, segment.header)
                && Arrays.equals(body, segment.body) && Arrays.equals(rolledBack, segment.rolledBack);
    }

    @Override
    public int hashCode() {
        return (Long.hashCode(firstId) * 31 + Arrays.hashCode(header)) * 31 + Arrays.hashCode(body);
    }

    @Override
    public @NotNull String toString() {
        return "PackedSegment[ids=" + firstId + ".." + lastId + ", events=" + events + ", header=" + header.length
                + " bytes, body=" + body.length + " bytes, rolledBack=" + rolledBack.length + "]";
    }
}
