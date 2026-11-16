package gg.moonrise.hawkeye.config;

import org.jetbrains.annotations.NotNull;

/** Outcome of {@link HawkeyeConfigProvider#reloadConfig()}. */
public sealed interface ReloadResult {

    /** The file was read and its snapshot is now active. */
    record Reloaded() implements ReloadResult {
    }

    /** The file could not be read or written; the previous snapshot stays active. */
    record Failed(@NotNull String reason) implements ReloadResult {
    }
}
