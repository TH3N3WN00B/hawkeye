package gg.moonrise.hawkeye.config.provider;

import java.util.List;
import org.jetbrains.annotations.NotNull;

/** Outcome of {@link HawkeyeConfigProvider#reloadConfig()}. */
public sealed interface ReloadResult {

    /**
     * Both files were read and their snapshot is now active.
     *
     * @param restartRequired sections that changed but only apply after a restart
     */
    record Reloaded(@NotNull List<String> restartRequired) implements ReloadResult {

        /**
         * Copies the list.
         *
         * @param restartRequired sections that need a restart
         */
        public Reloaded {
            restartRequired = List.copyOf(restartRequired);
        }
    }

    /**
     * A file could not be read, written or validated; the previous snapshot stays active.
     *
     * @param reason what is wrong
     */
    record Failed(@NotNull String reason) implements ReloadResult {
    }
}
