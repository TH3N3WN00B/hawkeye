package gg.moonrise.hawkeye.core.service.lookup;

import org.jetbrains.annotations.NotNull;

/** How many events a lookup matched, as far as its count went. */
public sealed interface LookupTotal {

    /**
     * The total of a count outcome, or why there is none.
     *
     * @param outcome what the count returned
     * @return {@link Exact} or {@link AtLeast} for a count, {@link Unknown} for anything else
     */
    static @NotNull LookupTotal of(@NotNull LookupOutcome outcome) {
        return switch (outcome) {
            case LookupOutcome.Count count -> count.exact() ? new Exact(count.total()) : new AtLeast(count.total());
            case LookupOutcome.Page page -> new Unknown();
            case LookupOutcome.Problem problem -> new Unknown();
        };
    }

    /**
     * Every matching event was counted.
     *
     * @param events the number of matching events
     */
    record Exact(long events) implements LookupTotal {
    }

    /**
     * Counting stopped at its budget: at least this many events match, probably more.
     *
     * @param events the events counted
     */
    record AtLeast(long events) implements LookupTotal {
    }

    /** The events could not be counted. */
    record Unknown() implements LookupTotal {
    }
}
