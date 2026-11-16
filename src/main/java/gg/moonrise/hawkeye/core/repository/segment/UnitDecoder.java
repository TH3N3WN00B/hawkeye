package gg.moonrise.hawkeye.core.repository.segment;

import gg.moonrise.hawkeye.core.repository.EventRow;
import gg.moonrise.hawkeye.util.codec.CodecException;
import java.util.Arrays;
import java.util.List;
import org.jetbrains.annotations.NotNull;

/** Decodes every row of one unit ({@link UnitColumns} describes the layout, {@link UnitScan} reads it). */
public final class UnitDecoder {

    private UnitDecoder() {
    }

    /**
     * Decodes a unit and appends its rows, in stored order.
     *
     * @param header     the segment header
     * @param shared     the decoded shared frames
     * @param unit       the unit number
     * @param bytes      an array holding the unit's frames
     * @param offset     where they start in it
     * @param length     their length; must equal {@code header.unit(unit).length()}
     * @param rolledBack ascending ordinals to flag {@link EventRow#ROLLED_BACK}
     * @param out        where the rows go
     * @throws CodecException when the frames are corrupt
     */
    public static void decode(@NotNull SegmentHeader header, @NotNull SharedColumns shared, int unit,
            byte @NotNull [] bytes, int offset, int length, int @NotNull [] rolledBack, @NotNull List<EventRow> out) {
        UnitScan scan = new UnitScan(header, unit, bytes, offset, length);
        scan.checkAll();
        int start = scan.start();
        int flagAt = firstAtOrAfter(rolledBack, start);
        long[] ids = shared.ids();
        Palettes palettes = shared.palettes();
        for (int i = 0; i < scan.rows(); i++) {
            int ordinal = start + i;
            int flags = 0;
            if (flagAt < rolledBack.length && rolledBack[flagAt] == ordinal) {
                flags = EventRow.ROLLED_BACK;
                flagAt++;
            }
            out.add(scan.row(i, ids[ordinal], flags, palettes));
        }
    }

    /**
     * The index of the first ordinal at or after a value.
     *
     * @param sorted ascending ordinals
     * @param value  the value
     * @return the index, {@code sorted.length} when none
     */
    public static int firstAtOrAfter(int @NotNull [] sorted, int value) {
        int at = Arrays.binarySearch(sorted, value);
        return at >= 0 ? at : -at - 1;
    }
}
