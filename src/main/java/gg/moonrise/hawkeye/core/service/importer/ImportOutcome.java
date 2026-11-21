package gg.moonrise.hawkeye.core.service.importer;

import org.jetbrains.annotations.NotNull;

/** How an import ended, or why it did not start. */
public sealed interface ImportOutcome {

    /**
     * Every row of the source was read; the checkpoint is marked complete.
     *
     * @param totals the run's final progress
     */
    record Finished(@NotNull ImportProgress totals) implements ImportOutcome {
    }

    /**
     * The run was cancelled; what it mapped is written with its checkpoint, and running the import again
     * continues from there.
     *
     * @param totals the run's progress when it stopped
     */
    record Cancelled(@NotNull ImportProgress totals) implements ImportOutcome {
    }

    /** Another import is running on this server. */
    record AlreadyRunning() implements ImportOutcome {
    }

    /** The home database is not connected yet. */
    record NotReady() implements ImportOutcome {
    }

    /**
     * No CoreProtect config or database is where the argument points.
     *
     * @param path the path looked at
     */
    record SourceMissing(@NotNull String path) implements ImportOutcome {
    }

    /**
     * A CoreProtect config was found but cannot be used.
     *
     * @param reason what is wrong
     */
    record SourceInvalid(@NotNull String reason) implements ImportOutcome {
    }

    /**
     * The source is a DuckDB file and CoreProtect is enabled on this server: one process cannot open a
     * DuckDB file twice.
     */
    record CoreProtectRunning() implements ImportOutcome {
    }

    /** A CoreProtect migration of the source is incomplete. */
    record SourceMigrating() implements ImportOutcome {
    }

    /**
     * The stored checkpoint was taken in another scan order than the source has now (the database moved
     * to or from ClickHouse), so its cursors do not apply.
     */
    record OrderChanged() implements ImportOutcome {
    }

    /** Another run moved this source's checkpoint (an import of it on another server): this run stopped. */
    record Superseded() implements ImportOutcome {
    }

    /**
     * Reading or writing failed; everything up to the last checkpoint stays imported.
     *
     * @param reason what went wrong
     */
    record Failed(@NotNull String reason) implements ImportOutcome {
    }
}
