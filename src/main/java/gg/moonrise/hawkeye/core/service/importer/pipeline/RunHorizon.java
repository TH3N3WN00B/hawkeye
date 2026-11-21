package gg.moonrise.hawkeye.core.service.importer.pipeline;

import gg.moonrise.hawkeye.entity.importer.source.SourceRow;
import org.jetbrains.annotations.NotNull;

/**
 * The latest second one run imports, and how rows past it are treated. A run imports rows stamped up to
 * its start minus the source's settle time (rows a server engine may still be making visible); later
 * rows are left unread for the next run.
 *
 * <p>In rowid order a row stamped more than {@link #FUTURE_SECONDS} past the horizon is a clock that ran
 * ahead, not a new row: it lies beyond the {@link #ceiling()}, the merge returns it first and the mapper
 * skips it, so it cannot hold back the rows behind it in its table. In time order (ClickHouse) no row
 * past the horizon is ever consumed: future rows sort last there and stay unread, and consuming one would
 * move the table's keyset cursor past every later row. The ceiling is then {@link Long#MAX_VALUE}.
 *
 * @param seconds the last second imported (Unix seconds); rows stamped later are left for the next run
 * @param ceiling the latest time a row can sensibly have; rows stamped later are consumed first and skipped
 */
public record RunHorizon(long seconds, long ceiling) {

    /** How far past the horizon a row's time may lie in rowid order before it counts as wrong, not as new. */
    public static final long FUTURE_SECONDS = 7L * 24 * 60 * 60;

    /**
     * The horizon of a run.
     *
     * @param startedMillis when the run began
     * @param settleSeconds how long before that the run's rows end
     * @param timeOrder     whether the source is read in time order
     * @return the horizon
     */
    public static @NotNull RunHorizon of(long startedMillis, long settleSeconds, boolean timeOrder) {
        long seconds = Math.floorDiv(startedMillis, 1000L) - Math.max(0, settleSeconds);
        return new RunHorizon(seconds, timeOrder ? Long.MAX_VALUE : seconds + FUTURE_SECONDS);
    }

    /**
     * Whether a row is left for a later run: stamped after the horizon, but not beyond the ceiling.
     *
     * @param row the row
     * @return {@code true} when it must stay unread
     */
    public boolean leftForLater(@NotNull SourceRow row) {
        return row.time() > seconds && row.time() <= ceiling;
    }

    /**
     * Whether a row sorts first because its time lies beyond the ceiling.
     *
     * @param time the row's time
     * @return {@code true} for a time past the ceiling
     */
    public boolean beyondCeiling(long time) {
        return time > ceiling;
    }

    /**
     * The last millisecond imported, for the mapper's upper bound: every row stamped after the horizon
     * (only rows beyond the ceiling get that far) is skipped.
     *
     * @return epoch milliseconds
     */
    public long lastMillis() {
        return seconds * 1000L + 999;
    }
}
