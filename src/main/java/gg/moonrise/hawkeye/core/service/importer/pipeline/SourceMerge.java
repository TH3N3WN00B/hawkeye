package gg.moonrise.hawkeye.core.service.importer.pipeline;

import gg.moonrise.hawkeye.entity.importer.SourceCursor;
import gg.moonrise.hawkeye.entity.importer.SourceRow;
import gg.moonrise.hawkeye.entity.importer.SourceTable;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.function.Function;
import org.jetbrains.annotations.NotNull;

/**
 * A k-way merge of a source's event tables by time: one stream per table, each read in pages in the
 * table's own scan order from its cursor, and always the stream whose next row has the smallest
 * {@code time} next (ties by {@link SourceTable} order). A stream ends when a read returns no rows.
 * Rows stamped later than the merge's ceiling (a clock that ran far ahead) come first instead of last, so
 * such a row never holds back the rows behind it in its table.
 * Each table is read in its cheap physical order, so the merged rows are only as time-ordered as the
 * tables are; the import's id assigner absorbs rows that are a little out of order.
 */
public final class SourceMerge {

    private final @NotNull Reader reader;
    private final int page;
    private final long ceiling;
    private final PriorityQueue<Stream> heads = new PriorityQueue<>(Comparator
            .comparingLong(this::order)
            .thenComparing(stream -> stream.table));

    /**
     * Reads the first page of every table.
     *
     * @param reader  reads a page of a table
     * @param tables  the tables to merge
     * @param cursors where each table continues
     * @param page    the most rows one read returns
     * @param ceiling the latest time (Unix seconds) a row can sensibly have; later rows are returned first
     * @throws SQLException when a read fails
     */
    public SourceMerge(@NotNull Reader reader, @NotNull Collection<SourceTable> tables,
            @NotNull Function<SourceTable, SourceCursor> cursors, int page, long ceiling) throws SQLException {
        if (page < 1) {
            throw new IllegalArgumentException("a page holds at least one row");
        }
        this.reader = reader;
        this.page = page;
        this.ceiling = ceiling;
        for (SourceTable table : tables) {
            Stream stream = new Stream(table, cursors.apply(table));
            if (stream.fill(reader, page)) {
                heads.add(stream);
            }
        }
    }

    /**
     * The row {@link #next()} returns, without consuming it.
     *
     * @return the row, or empty when every table is read to its end
     */
    public @NotNull Optional<SourceRow> peek() {
        Stream head = heads.peek();
        return head == null ? Optional.empty() : Optional.of(head.rows.getFirst());
    }

    /**
     * Consumes the next row in merge order.
     *
     * @return the row
     * @throws SQLException           when reading the stream's next page fails
     * @throws IllegalStateException  when every table is read to its end
     */
    public @NotNull SourceRow next() throws SQLException {
        Stream head = heads.poll();
        if (head == null) {
            throw new IllegalStateException("every table is read to its end");
        }
        SourceRow row = head.rows.removeFirst();
        if (!head.rows.isEmpty() || head.fill(reader, page)) {
            heads.add(head);
        }
        return row;
    }

    /** Where a stream's head sorts: by its time, or before everything when that lies beyond the ceiling. */
    private long order(@NotNull Stream stream) {
        long time = stream.rows.getFirst().time();
        return time > ceiling ? Long.MIN_VALUE : time;
    }

    /** Reads a page of one table. */
    @FunctionalInterface
    public interface Reader {

        /**
         * The next rows of a table after a cursor, in its scan order.
         *
         * @param table the table
         * @param after the last row read
         * @param limit the most rows
         * @return the rows; empty when the table has no more
         * @throws SQLException when reading fails
         */
        @NotNull List<SourceRow> read(@NotNull SourceTable table, @NotNull SourceCursor after, int limit)
                throws SQLException;
    }

    /** One table's buffered rows and where its next page starts. */
    private static final class Stream {

        private final @NotNull SourceTable table;
        private final Deque<SourceRow> rows = new ArrayDeque<>();
        private @NotNull SourceCursor after;

        private Stream(@NotNull SourceTable table, @NotNull SourceCursor after) {
            this.table = table;
            this.after = after;
        }

        /** Reads the next page; {@code false} when it was empty (the table is read to its end). */
        private boolean fill(@NotNull Reader reader, int page) throws SQLException {
            List<SourceRow> next = reader.read(table, after, page);
            if (next.isEmpty()) {
                return false;
            }
            rows.addAll(next);
            after = next.getLast().cursor();
            return true;
        }
    }
}
