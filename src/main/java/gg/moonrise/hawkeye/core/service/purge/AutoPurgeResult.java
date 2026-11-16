package gg.moonrise.hawkeye.core.service.purge;

import org.jetbrains.annotations.NotNull;

/** What one automatic purge run did. */
public sealed interface AutoPurgeResult {

    /** {@code purge.retention-days} is 0: nothing is purged automatically. */
    record Disabled() implements AutoPurgeResult {
    }

    /** The home database is not connected yet. */
    record NotReady() implements AutoPurgeResult {
    }

    /** Another server of this shard holds the purge lease and purges for everyone. */
    record NotLeaseHolder() implements AutoPurgeResult {
    }

    /**
     * This server holds the lease and purged.
     *
     * @param outcome the purge's outcome
     */
    record Ran(@NotNull PurgeOutcome outcome) implements AutoPurgeResult {
    }

    /**
     * Taking the lease failed.
     *
     * @param reason what went wrong
     */
    record Failed(@NotNull String reason) implements AutoPurgeResult {
    }
}
