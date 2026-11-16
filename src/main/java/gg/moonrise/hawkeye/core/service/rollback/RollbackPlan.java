package gg.moonrise.hawkeye.core.service.rollback;

import java.util.List;
import java.util.stream.Stream;
import org.jetbrains.annotations.NotNull;

/**
 * Everything a rollback or restore changes, grouped by chunk in the order the chunks are applied,
 * and the events to flag once it is applied.
 *
 * @param mode     the mode
 * @param chunks   the chunks, nearest to the issuer first
 * @param eventIds the events this plan undoes or redoes
 */
public record RollbackPlan(@NotNull RollbackMode mode, @NotNull List<ChunkPlan> chunks, @NotNull List<Long> eventIds) {

    /**
     * Copies the lists.
     *
     * @param mode     the mode
     * @param chunks   the chunks
     * @param eventIds the events
     */
    public RollbackPlan {
        chunks = List.copyOf(chunks);
        eventIds = List.copyOf(eventIds);
    }

    /**
     * Whether the plan does nothing.
     *
     * @return {@code true} when no event is undone or redone
     */
    public boolean isEmpty() {
        return eventIds.isEmpty();
    }

    /**
     * Every change, chunk by chunk.
     *
     * @return the changes
     */
    public @NotNull Stream<PlannedChange> changes() {
        return chunks.stream().flatMap(chunk -> chunk.changes().stream());
    }

    /**
     * Block states set.
     *
     * @return the number of {@link PlannedChange.SetBlock} changes
     */
    public int blocks() {
        return (int) changes().filter(PlannedChange.SetBlock.class::isInstance).count();
    }
}
