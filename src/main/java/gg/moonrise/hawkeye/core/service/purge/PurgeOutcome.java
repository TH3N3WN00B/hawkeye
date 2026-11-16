package gg.moonrise.hawkeye.core.service.purge;

import org.jetbrains.annotations.NotNull;

/** How a purge ended. */
public sealed interface PurgeOutcome {

    /**
     * The events were deleted.
     *
     * @param events how many
     * @param millis how long it took
     */
    record Purged(long events, long millis) implements PurgeOutcome {

        /**
         * How long it took.
         *
         * @return seconds
         */
        public double seconds() {
            return millis / 1000.0;
        }
    }

    /**
     * A player asked for data younger than {@code purge.player-minimum-days}.
     *
     * @param minimumDays the limit
     */
    record TooRecent(int minimumDays) implements PurgeOutcome {
    }

    /** Another purge is still running. */
    record AlreadyRunning() implements PurgeOutcome {
    }

    /** The home database is not connected yet. */
    record NotReady() implements PurgeOutcome {
    }

    /**
     * Deleting failed; the batches before the failure stay deleted.
     *
     * @param reason what went wrong
     */
    record Failed(@NotNull String reason) implements PurgeOutcome {
    }
}
