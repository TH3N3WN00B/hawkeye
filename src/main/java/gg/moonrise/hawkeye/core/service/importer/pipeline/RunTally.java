package gg.moonrise.hawkeye.core.service.importer.pipeline;

import gg.moonrise.hawkeye.core.service.importer.ImportProgress;
import gg.moonrise.hawkeye.entity.importer.ImportState;
import gg.moonrise.hawkeye.entity.importer.SkipReason;
import gg.moonrise.hawkeye.entity.importer.source.SourceCursor;
import gg.moonrise.hawkeye.entity.importer.source.SourceRow;
import gg.moonrise.hawkeye.entity.importer.source.SourceTable;
import java.util.EnumMap;
import java.util.Map;
import org.jetbrains.annotations.NotNull;

/**
 * The counters and cursors of one run, on top of the checkpoint it started from: the next checkpoint
 * is the starting one plus everything this run consumed. Used by the import thread only.
 */
public final class RunTally {

    private final @NotNull ImportState start;
    private final Map<SourceTable, SourceCursor> cursors = new EnumMap<>(SourceTable.class);
    private final Map<SkipReason, Long> skipped = new EnumMap<>(SkipReason.class);
    private long rowsRead;
    private long sinceCheckpoint;
    private long imported;
    private long displaced;

    /**
     * A tally from a checkpoint.
     *
     * @param start the checkpoint the run started from
     */
    public RunTally(@NotNull ImportState start) {
        this.start = start;
        cursors.putAll(start.cursors());
    }

    /**
     * Records a consumed row: its table continues after it.
     *
     * @param row the row
     */
    public void consumed(@NotNull SourceRow row) {
        cursors.put(row.table(), row.cursor());
        rowsRead++;
        sinceCheckpoint++;
    }

    /**
     * Moves a table's cursor without consuming a row: the source was renumbered since the checkpoint
     * ({@code CoreProtectSource#anchor}).
     *
     * @param table  the table
     * @param cursor where it continues now
     */
    public void reanchor(@NotNull SourceTable table, @NotNull SourceCursor cursor) {
        cursors.put(table, cursor);
    }

    /**
     * Counts a skipped row.
     *
     * @param reason why
     */
    public void skipped(@NotNull SkipReason reason) {
        skipped.merge(reason, 1L, Long::sum);
    }

    /**
     * Counts a written checkpoint.
     *
     * @param events    the events its segment held
     * @param displaced how many of them were older than the id floor
     */
    public void committed(int events, long displaced) {
        imported += events;
        this.displaced += displaced;
        sinceCheckpoint = 0;
    }

    /**
     * Rows consumed since the last checkpoint.
     *
     * @return the count
     */
    public long sinceCheckpoint() {
        return sinceCheckpoint;
    }

    /**
     * Where a table continues.
     *
     * @param table the table
     * @return the last consumed row's cursor, or the checkpoint's
     */
    public @NotNull SourceCursor cursor(@NotNull SourceTable table) {
        return cursors.getOrDefault(table, SourceCursor.START);
    }

    /**
     * The checkpoint after everything consumed so far.
     *
     * @param holding    the checkpoint the run holds now (its revision is compared)
     * @param newestSlot the newest slot written
     * @param displaced  events of the pending segment older than the id floor
     * @param events     events in the pending segment
     * @param complete   whether every table is read to its end
     * @param nowMillis  the current time
     * @return the state to write
     */
    public @NotNull ImportState next(@NotNull ImportState holding, long newestSlot, long displaced, int events,
            boolean complete, long nowMillis) {
        Map<SkipReason, Long> skips = start.skippedCopy();
        skipped.forEach((reason, count) -> skips.merge(reason, count, Long::sum));
        return new ImportState(holding.revision(), holding.node(), holding.order(), cursors, newestSlot,
                start.imported() + imported + events, start.displaced() + this.displaced + displaced, skips,
                complete, start.startedMillis(), nowMillis);
    }

    /**
     * A progress snapshot.
     *
     * @param source        the source's description
     * @param resumed       whether the run continues an earlier import
     * @param remaining     the estimate of rows left
     * @param elapsedMillis how long the run has been going
     * @return the snapshot
     */
    public @NotNull ImportProgress progress(@NotNull String source, boolean resumed, long remaining,
            long elapsedMillis) {
        return new ImportProgress(source, resumed, start.imported() + imported, imported, rowsRead, remaining,
                skipped, displaced, elapsedMillis);
    }
}
