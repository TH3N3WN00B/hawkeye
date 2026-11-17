package gg.moonrise.hawkeye.core.service.lookup;

import gg.moonrise.hawkeye.entity.event.StoredEvent;
import java.util.List;
import org.jetbrains.annotations.NotNull;

/** Outcome of {@link LookupService#page} and {@link LookupService#count}. */
public sealed interface LookupOutcome {

    /**
     * One page of events, newest first.
     *
     * @param events   at most the page size, newest first
     * @param hasMore  whether older events follow
     * @param warnings shards that could not be read (the page holds the others)
     */
    record Page(@NotNull List<StoredEvent> events, boolean hasMore, @NotNull List<LookupWarning> warnings)
            implements LookupOutcome {

        /**
         * Copies the lists.
         *
         * @param events   the events
         * @param hasMore  whether older events follow
         * @param warnings unreadable shards
         */
        public Page {
            events = List.copyOf(events);
            warnings = List.copyOf(warnings);
        }

        /**
         * The cursor of the page after this one.
         *
         * @return the id of the last event, or 0 when the page is empty
         */
        public long nextCursor() {
            return events.isEmpty() ? 0 : events.getLast().id();
        }
    }

    /**
     * How many events match.
     *
     * @param total    the number of matching events in the shards that could be read
     * @param exact    whether every candidate was counted; when not, {@code total} is a lower bound (a
     *                 count with a segment budget stopped early)
     * @param warnings shards that could not be read
     */
    record Count(long total, boolean exact, @NotNull List<LookupWarning> warnings) implements LookupOutcome {

        /**
         * Copies the warnings.
         *
         * @param total    the count
         * @param exact    whether it is exact
         * @param warnings unreadable shards
         */
        public Count {
            warnings = List.copyOf(warnings);
        }
    }

    /** Why a lookup has no result at all. */
    sealed interface Problem extends LookupOutcome {
    }

    /** The home database is still connecting or unreachable. */
    record NotReady() implements Problem {
    }

    /**
     * The lookup failed: every shard failed, the server registry could not be read, or the query pool
     * is full.
     *
     * @param reason what went wrong
     */
    record Failed(@NotNull String reason) implements Problem {
    }

    /** The lookup ran past {@code lookup.timeout-seconds} and was cancelled. */
    record TimedOut() implements Problem {
    }
}
