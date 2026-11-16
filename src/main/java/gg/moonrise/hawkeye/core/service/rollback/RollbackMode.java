package gg.moonrise.hawkeye.core.service.rollback;

import gg.moonrise.hawkeye.entity.query.RollbackState;
import org.jetbrains.annotations.NotNull;

/** Which way an operation turns the recorded changes. */
public enum RollbackMode {

    /** Undo changes: put back what was there before them. */
    ROLLBACK,
    /** Redo rolled-back changes: put back what they made. */
    RESTORE;

    /**
     * The mode that reverses this one ({@code /hawkeye undo}).
     *
     * @return the other mode
     */
    public @NotNull RollbackMode opposite() {
        return this == ROLLBACK ? RESTORE : ROLLBACK;
    }

    /**
     * Which events this mode reads: a rollback undoes active events, a restore redoes rolled-back ones.
     *
     * @return the rollback-state filter
     */
    public @NotNull RollbackState reads() {
        return this == ROLLBACK ? RollbackState.ACTIVE : RollbackState.ROLLED_BACK;
    }

    /**
     * The rolled-back flag the events carry once this mode has applied them.
     *
     * @return {@code true} for a rollback
     */
    public boolean flagsRolledBack() {
        return this == ROLLBACK;
    }
}
