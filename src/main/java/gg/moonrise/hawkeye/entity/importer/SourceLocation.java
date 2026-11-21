package gg.moonrise.hawkeye.entity.importer;

import org.jetbrains.annotations.NotNull;

/** What the operator's argument to an import resolved to. */
public sealed interface SourceLocation {

    /**
     * A CoreProtect database was found.
     *
     * @param settings how to open it
     */
    record Found(@NotNull SourceSettings settings) implements SourceLocation {
    }

    /**
     * No CoreProtect config or database is there.
     *
     * @param path the path that was looked at
     */
    record Missing(@NotNull String path) implements SourceLocation {
    }

    /**
     * A config was found but cannot be used: unreadable, an unknown {@code database-type}, a bad prefix.
     *
     * @param reason what is wrong, for the operator
     */
    record Invalid(@NotNull String reason) implements SourceLocation {
    }
}
