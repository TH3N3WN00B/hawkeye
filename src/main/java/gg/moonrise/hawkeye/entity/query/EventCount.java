package gg.moonrise.hawkeye.entity.query;

import org.jetbrains.annotations.NotNull;

/**
 * How many events match a filter, from a count that may have stopped early.
 *
 * @param total the events counted
 * @param exact whether every candidate was counted; when not, {@code total} is a lower bound
 */
public record EventCount(long total, boolean exact) {

    /**
     * An exact count.
     *
     * @param total the number of matching events
     * @return the count
     */
    public static @NotNull EventCount of(long total) {
        return new EventCount(total, true);
    }

    /**
     * A lower bound: counting stopped with candidates left.
     *
     * @param total the events counted before it stopped
     * @return the count
     */
    public static @NotNull EventCount atLeast(long total) {
        return new EventCount(total, false);
    }

    /**
     * The sum of two counts, exact only when both are.
     *
     * @param other the other count
     * @return the sum
     */
    public @NotNull EventCount plus(@NotNull EventCount other) {
        return new EventCount(total + other.total, exact && other.exact);
    }
}
