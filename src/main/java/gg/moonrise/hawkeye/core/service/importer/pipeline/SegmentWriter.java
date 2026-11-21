package gg.moonrise.hawkeye.core.service.importer.pipeline;

import gg.moonrise.hawkeye.core.queue.id.IdAssigner;
import gg.moonrise.hawkeye.core.storage.importer.ImportStore;
import gg.moonrise.hawkeye.core.storage.shard.Shard;
import gg.moonrise.hawkeye.entity.event.PendingEvent;
import gg.moonrise.hawkeye.entity.importer.CheckpointResult;
import gg.moonrise.hawkeye.entity.importer.ImportState;
import gg.moonrise.hawkeye.entity.server.ServerNode;
import gg.moonrise.hawkeye.util.event.EventIds;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.Executor;
import org.jetbrains.annotations.NotNull;

/**
 * Closes an import's batches: numbers each batch with the run's own {@link IdAssigner} (fully taken, so
 * one batch is one segment) and writes it with the checkpoint that accounts for it, in one transaction
 * ({@link ImportStore#write}). On a resumed import the assigner's floor is the slot after the newest one
 * already written, so no id is issued twice; an event older than the floor is stored in the floor's
 * second and counted as displaced.
 */
public final class SegmentWriter {

    private final @NotNull ImportStore store;
    private final @NotNull Shard home;
    private final @NotNull ServerNode node;
    private final @NotNull String scope;
    private final @NotNull String source;
    private final @NotNull Executor executor;
    private final IdAssigner assigner = new IdAssigner();
    private @NotNull ImportState holding;

    /**
     * A writer continuing from a checkpoint.
     *
     * @param store    the import storage
     * @param home     the home shard
     * @param node     the import node
     * @param scope    the world scope events are stored under
     * @param source   the source key
     * @param executor where segment frames are compressed
     * @param holding  the checkpoint the run starts from
     */
    public SegmentWriter(@NotNull ImportStore store, @NotNull Shard home, @NotNull ServerNode node,
            @NotNull String scope, @NotNull String source, @NotNull Executor executor, @NotNull ImportState holding) {
        this.store = store;
        this.home = home;
        this.node = node;
        this.scope = scope;
        this.source = source;
        this.executor = executor;
        this.holding = holding;
        if (holding.imported() > 0) {
            assigner.floor(holding.newestSlot() + 1);
        }
    }

    /**
     * The checkpoint the run holds.
     *
     * @return the last state written (or the starting one)
     */
    public @NotNull ImportState holding() {
        return holding;
    }

    /**
     * Writes a batch and its checkpoint. Writes nothing when nothing was consumed since the last
     * checkpoint and the completion mark is already right.
     *
     * @param batch     the mapped events, with capture stamps, in source order
     * @param tally     the run's counters and cursors
     * @param complete  whether every table is read to its end
     * @param nowMillis the current time
     * @return the stored checkpoint, or superseded when another run moved it
     * @throws SQLException when writing fails
     */
    public @NotNull CheckpointResult write(@NotNull List<PendingEvent> batch, @NotNull RunTally tally,
            boolean complete, long nowMillis) throws SQLException {
        if (batch.isEmpty() && tally.sinceCheckpoint() == 0 && complete == holding.complete()) {
            return new CheckpointResult.Saved(holding);
        }
        long floor = assigner.floorSlot();
        long displaced = batch.stream().filter(event -> EventIds.stampSlot(event.stamp()) < floor).count();
        assigner.add(batch);
        List<PendingEvent> numbered = assigner.take(Long.MAX_VALUE / 2, true);
        long newestSlot = numbered.stream().mapToLong(event -> EventIds.stampSlot(event.stamp()))
                .max().orElse(holding.newestSlot());
        ImportState next = tally.next(holding, Math.max(newestSlot, holding.newestSlot()), displaced,
                numbered.size(), complete, nowMillis);
        CheckpointResult result = numbered.isEmpty() ? store.save(home, source, next)
                : store.write(home, node, scope, numbered, executor, source, next);
        if (result instanceof CheckpointResult.Saved saved) {
            holding = saved.state();
            tally.committed(numbered.size(), displaced);
        }
        return result;
    }
}
