package gg.moonrise.hawkeye.core.service.importer;

import org.jetbrains.annotations.NotNull;

/**
 * Hears how an import goes while it runs; the final result is the returned {@link ImportOutcome}. The
 * command implements it to tell the sender. Calls arrive on the import thread: hop to the right thread
 * before touching Bukkit state.
 */
public interface ImportListener {

    /** Hears nothing. */
    ImportListener NONE = new ImportListener() {
    };

    /**
     * The source is open and reading begins.
     *
     * @param progress where the import stands ({@link ImportProgress#resumed()} for a continued one)
     */
    default void started(@NotNull ImportProgress progress) {
    }

    /**
     * The import is still going; called about every {@link ImportService#PROGRESS_INTERVAL}.
     *
     * @param progress where it stands
     */
    default void progress(@NotNull ImportProgress progress) {
    }
}
