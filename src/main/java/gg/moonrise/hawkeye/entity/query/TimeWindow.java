package gg.moonrise.hawkeye.entity.query;

import gg.moonrise.hawkeye.util.EventIds;
import org.jetbrains.annotations.NotNull;

/**
 * An inclusive window of event times.
 *
 * @param fromMillis the earliest Unix millisecond, or {@link Long#MIN_VALUE} for no lower bound
 * @param toMillis   the latest Unix millisecond, or {@link Long#MAX_VALUE} for no upper bound
 */
public record TimeWindow(long fromMillis, long toMillis) {

    /** Every event ever written. */
    public static final TimeWindow ALL = new TimeWindow(Long.MIN_VALUE, Long.MAX_VALUE);

    /**
     * Validates the order.
     *
     * @param fromMillis the earliest millisecond
     * @param toMillis   the latest millisecond
     */
    public TimeWindow {
        if (fromMillis > toMillis) {
            throw new IllegalArgumentException("time window ends before it starts");
        }
    }

    /**
     * The window from {@code nowMillis - ageMillis} to now.
     *
     * @param nowMillis the current time
     * @param ageMillis how far back
     * @return the window
     */
    public static @NotNull TimeWindow last(long nowMillis, long ageMillis) {
        return new TimeWindow(nowMillis - ageMillis, nowMillis);
    }

    /**
     * The smallest event id inside the window.
     *
     * @return the lower id bound
     */
    public long minId() {
        return fromMillis == Long.MIN_VALUE ? 0 : EventIds.floor(fromMillis);
    }

    /**
     * The largest event id inside the window.
     *
     * @return the upper id bound
     */
    public long maxId() {
        return toMillis == Long.MAX_VALUE ? Long.MAX_VALUE : EventIds.ceiling(toMillis);
    }

    /**
     * Whether the window has a lower bound.
     *
     * @return {@code false} for an open start
     */
    public boolean bounded() {
        return fromMillis != Long.MIN_VALUE;
    }
}
