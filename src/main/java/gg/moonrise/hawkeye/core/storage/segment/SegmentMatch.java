package gg.moonrise.hawkeye.core.storage.segment;

import gg.moonrise.hawkeye.core.repository.event.EventRow;
import gg.moonrise.hawkeye.util.codec.CodecException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongPredicate;
import org.jetbrains.annotations.NotNull;

/**
 * The rows of some units of a segment whose columns passed an {@code EventFilter}'s column rules
 * ({@link Segments#match}): row numbers only, no ids, no {@link EventRow} objects, no payloads. The
 * rules that need ids are applied afterwards ({@link #count(SharedColumns, LongPredicate)},
 * {@link #rows}), and only when something matched.
 *
 * @see gg.moonrise.hawkeye.core.repository.event.EventFilter#matchesId
 */
public final class SegmentMatch {

    private final List<UnitScan> scans;
    private final List<int[]> matches;
    private final int[] rolledBack;
    private final int count;

    /**
     * The matches of some units.
     *
     * @param scans      the scanned units
     * @param matches    per unit: its matching row numbers, ascending
     * @param rolledBack the segment's ascending rolled-back ordinals
     */
    public SegmentMatch(@NotNull List<UnitScan> scans, @NotNull List<int[]> matches, int @NotNull [] rolledBack) {
        if (scans.size() != matches.size()) {
            throw new IllegalArgumentException(scans.size() + " units but " + matches.size() + " match lists");
        }
        this.scans = List.copyOf(scans);
        this.matches = List.copyOf(matches);
        this.rolledBack = rolledBack;
        this.count = matches.stream().mapToInt(rows -> rows.length).sum();
    }

    /**
     * How many rows passed the column rules.
     *
     * @return the count
     */
    public int count() {
        return count;
    }

    /**
     * The ordinals of the matching rows, unit by unit in the order scanned.
     *
     * @return the ordinals
     */
    public int @NotNull [] ordinals() {
        int[] out = new int[count];
        int size = 0;
        for (int u = 0; u < scans.size(); u++) {
            int start = scans.get(u).start();
            for (int row : matches.get(u)) {
                out[size++] = start + row;
            }
        }
        return out;
    }

    /**
     * How many matching rows also pass an id rule; computes ids, builds no rows.
     *
     * @param shared the segment's decoded shared frames
     * @param idRule the rules that need the id
     * @return the count
     */
    public int count(@NotNull SharedColumns shared, @NotNull LongPredicate idRule) {
        long[] ids = shared.ids();
        int out = 0;
        for (int ordinal : ordinals()) {
            if (idRule.test(ids[ordinal])) {
                out++;
            }
        }
        return out;
    }

    /**
     * Builds the matching rows that also pass an id rule, with their flags. A unit's payload frame is
     * decompressed only when one of those rows has data.
     *
     * @param shared the segment's decoded shared frames
     * @param idRule the rules that need the id
     * @return the rows, unit by unit in the order scanned, each in stored order
     * @throws CodecException when a column is corrupt
     */
    public @NotNull List<EventRow> rows(@NotNull SharedColumns shared, @NotNull LongPredicate idRule) {
        long[] ids = shared.ids();
        List<EventRow> out = new ArrayList<>();
        for (int u = 0; u < scans.size(); u++) {
            UnitScan scan = scans.get(u);
            int start = scan.start();
            int flagAt = UnitDecoder.firstAtOrAfter(rolledBack, start);
            for (int row : matches.get(u)) {
                int ordinal = start + row;
                long id = ids[ordinal];
                if (!idRule.test(id)) {
                    continue;
                }
                while (flagAt < rolledBack.length && rolledBack[flagAt] < ordinal) {
                    flagAt++;
                }
                int flags = flagAt < rolledBack.length && rolledBack[flagAt] == ordinal ? EventRow.ROLLED_BACK : 0;
                out.add(scan.row(row, id, flags, shared.palettes()));
            }
        }
        return out;
    }
}
