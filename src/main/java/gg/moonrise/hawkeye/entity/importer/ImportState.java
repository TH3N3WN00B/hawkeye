package gg.moonrise.hawkeye.entity.importer;

import java.util.EnumMap;
import java.util.Map;
import org.jetbrains.annotations.NotNull;

/**
 * The checkpoint of one imported source, one {@code import_state} row. Every write of it compares
 * {@code revision} and raises it by one in the transaction that also writes the segment it accounts
 * for, so a crash or a second server never imports a row twice.
 *
 * @param revision      the stored revision this state was read at (or is written over)
 * @param node          the name of the import node the events are numbered under
 * @param order         the source's scan order: {@code rowid} or {@code time}
 * @param cursors       per table, the last row imported (a table without one starts at the beginning)
 * @param newestSlot    the newest id slot written; a resumed run starts its ids after it
 * @param imported      events written
 * @param displaced     events older than the id floor, stored in the floor's second
 * @param skipped       rows not imported, by reason
 * @param complete      whether the last run read every table to its end
 * @param startedMillis when the import of this source first began, Unix milliseconds
 * @param updatedMillis when the checkpoint was last written, Unix milliseconds
 */
public record ImportState(
        long revision,
        @NotNull String node,
        @NotNull String order,
        @NotNull Map<SourceTable, SourceCursor> cursors,
        long newestSlot,
        long imported,
        long displaced,
        @NotNull Map<SkipReason, Long> skipped,
        boolean complete,
        long startedMillis,
        long updatedMillis) {

    /** Copies the maps. */
    public ImportState {
        cursors = Map.copyOf(cursors);
        skipped = Map.copyOf(skipped);
    }

    /**
     * The state of a source nothing has been imported from yet, at revision 1.
     *
     * @param node      the import node's name
     * @param order     the source's scan order
     * @param nowMillis the current time
     * @return the state
     */
    public static @NotNull ImportState fresh(@NotNull String node, @NotNull String order, long nowMillis) {
        return new ImportState(1, node, order, Map.of(), 0, 0, 0, Map.of(), false, nowMillis, nowMillis);
    }

    /**
     * Where a table's reading continues.
     *
     * @param table the table
     * @return its cursor, or {@link SourceCursor#START}
     */
    public @NotNull SourceCursor cursor(@NotNull SourceTable table) {
        return cursors.getOrDefault(table, SourceCursor.START);
    }

    /**
     * How many rows were skipped for a reason.
     *
     * @param reason the reason
     * @return the count
     */
    public long skipped(@NotNull SkipReason reason) {
        return skipped.getOrDefault(reason, 0L);
    }

    /**
     * The rows skipped for every reason together.
     *
     * @return the sum
     */
    public long skippedTotal() {
        return skipped.values().stream().mapToLong(Long::longValue).sum();
    }

    /**
     * This state as stored at another revision and time (what a successful checkpoint returns).
     *
     * @param revision      the new revision
     * @param updatedMillis when it was written
     * @return the copy
     */
    public @NotNull ImportState withRevision(long revision, long updatedMillis) {
        return new ImportState(revision, node, order, cursors, newestSlot, imported, displaced, skipped, complete,
                startedMillis, updatedMillis);
    }

    /**
     * The skip counts as a mutable map to add to.
     *
     * @return a copy with every reason present
     */
    public @NotNull Map<SkipReason, Long> skippedCopy() {
        Map<SkipReason, Long> copy = new EnumMap<>(SkipReason.class);
        for (SkipReason reason : SkipReason.values()) {
            copy.put(reason, skipped(reason));
        }
        return copy;
    }
}
