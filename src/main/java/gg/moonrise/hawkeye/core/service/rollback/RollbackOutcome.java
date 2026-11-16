package gg.moonrise.hawkeye.core.service.rollback;

import org.jetbrains.annotations.NotNull;

/** How a rollback, restore, preview, apply, cancel or undo ended. */
public sealed interface RollbackOutcome {

    /**
     * The world was changed and the events flagged.
     *
     * @param mode     the mode
     * @param events   events flagged
     * @param blocks   blocks and sign texts changed
     * @param items    item stacks put into or taken out of containers, item frames and armour stands
     * @param entities entities respawned or removed
     * @param millis   how long it took
     */
    record Applied(@NotNull RollbackMode mode, int events, int blocks, int items, int entities, long millis)
            implements RollbackOutcome {

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
     * The planned blocks were sent to the issuing player only; {@code /hawkeye apply} or
     * {@code cancel} follows.
     *
     * @param mode    the mode
     * @param blocks  blocks shown
     * @param minutes minutes until the preview expires
     */
    record Previewed(@NotNull RollbackMode mode, int blocks, int minutes) implements RollbackOutcome {
    }

    /** The preview was dropped and the real blocks sent back. */
    record PreviewCancelled() implements RollbackOutcome {
    }

    /**
     * No event matched.
     *
     * @param mode the mode
     */
    record NothingToDo(@NotNull RollbackMode mode) implements RollbackOutcome {
    }

    /** {@code /hawkeye undo} without an earlier rollback or restore. */
    record NothingToUndo() implements RollbackOutcome {
    }

    /** {@code /hawkeye apply} or {@code cancel} without a preview. */
    record NoPreview() implements RollbackOutcome {
    }

    /**
     * More events matched than {@code rollback.max-events}.
     *
     * @param events how many matched
     * @param max    the limit
     */
    record TooLarge(long events, int max) implements RollbackOutcome {
    }

    /** The issuer's previous operation is still running. */
    record AlreadyRunning() implements RollbackOutcome {
    }

    /** The home database is not connected yet. */
    record NotReady() implements RollbackOutcome {
    }

    /**
     * The query names other servers. A server only changes its own worlds; other servers get network
     * jobs, which this build does not dispatch yet.
     */
    record NetworkUnsupported() implements RollbackOutcome {
    }

    /** {@code #preview} from a sender that is not a player. */
    record PreviewNeedsPlayer() implements RollbackOutcome {
    }

    /**
     * Reading, applying or flagging failed.
     *
     * @param reason what went wrong
     */
    record Failed(@NotNull String reason) implements RollbackOutcome {
    }
}
