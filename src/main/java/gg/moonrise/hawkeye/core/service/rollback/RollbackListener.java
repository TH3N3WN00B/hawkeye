package gg.moonrise.hawkeye.core.service.rollback;

import org.jetbrains.annotations.NotNull;

/**
 * Hears how an operation goes while it runs; the final result is the returned
 * {@link RollbackOutcome}. The command implements it to tell the sender, a network job handler to
 * report to the issuing server. Calls arrive on Hawkeye's own or a region thread: hop to the right
 * thread before touching Bukkit state.
 */
public interface RollbackListener {

    /** Hears nothing. */
    RollbackListener NONE = new RollbackListener() {
    };

    /**
     * The matching events were counted and the operation starts.
     *
     * @param mode   the mode
     * @param events how many events it reads
     */
    default void started(@NotNull RollbackMode mode, long events) {
    }

    /**
     * Some chunks are done; called at most every few seconds.
     *
     * @param mode  the mode
     * @param done  chunks done
     * @param total chunks in the plan
     */
    default void progress(@NotNull RollbackMode mode, int done, int total) {
    }

    /**
     * A preview was not applied in time and the real blocks were sent back.
     *
     * @param mode the previewed mode
     */
    default void previewExpired(@NotNull RollbackMode mode) {
    }
}
