package gg.moonrise.hawkeye.entity.importer;

import org.jetbrains.annotations.NotNull;

/** The outcome of writing an import checkpoint. */
public sealed interface CheckpointResult {

    /**
     * The checkpoint (and its segment, if any) is committed.
     *
     * @param state the stored state, carrying the new revision
     */
    record Saved(@NotNull ImportState state) implements CheckpointResult {
    }

    /** Another run moved the checkpoint first; nothing was written and this run must stop. */
    record Superseded() implements CheckpointResult {
    }
}
