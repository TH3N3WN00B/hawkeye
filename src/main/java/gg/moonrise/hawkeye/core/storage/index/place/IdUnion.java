package gg.moonrise.hawkeye.core.storage.index.place;

import java.util.BitSet;
import org.jetbrains.annotations.NotNull;

/**
 * The union of many {@link IdRuns} that overlap heavily (the segments of every block of an area inside
 * a busy chunk). Merging run lists one by one costs the size of the result each time; a bit set costs
 * only what is added. Values from {@link #DIRECT} on, which segment ids do not reach in practice, are
 * merged as runs. Not thread-safe.
 */
public final class IdUnion {

    /** Values below this are kept in a bit set (8 MB when the largest is set). */
    public static final long DIRECT = 1L << 26;

    private final BitSet low = new BitSet();
    private final IdRuns high = new IdRuns();

    /**
     * Adds every value of a set.
     *
     * @param runs the set
     */
    public void addAll(@NotNull IdRuns runs) {
        for (int run = 0; run < runs.runCount(); run++) {
            long from = runs.start(run);
            long to = runs.end(run);
            if (from < DIRECT) {
                low.set((int) from, (int) Math.min(to, DIRECT - 1) + 1);
            }
            if (to >= DIRECT) {
                high.addAll(IdRuns.range(Math.max(from, DIRECT), to));
            }
        }
    }

    /**
     * Everything added so far.
     *
     * @return a new set
     */
    public @NotNull IdRuns toRuns() {
        IdRuns out = new IdRuns();
        int from = low.nextSetBit(0);
        while (from >= 0) {
            int end = low.nextClearBit(from);
            out.append(from, end - 1L);
            from = low.nextSetBit(end);
        }
        out.addAll(high);
        return out;
    }

    @Override
    public @NotNull String toString() {
        return "IdUnion" + toRuns();
    }
}
