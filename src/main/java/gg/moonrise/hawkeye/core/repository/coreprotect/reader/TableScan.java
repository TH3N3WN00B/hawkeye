package gg.moonrise.hawkeye.core.repository.coreprotect.reader;

import gg.moonrise.hawkeye.entity.importer.source.SourceCursor;
import gg.moonrise.hawkeye.entity.importer.source.SourceRow;
import gg.moonrise.hawkeye.entity.importer.source.SourceTable;
import java.sql.SQLException;
import java.util.List;
import org.jetbrains.annotations.NotNull;

/** Walks a CoreProtect event table in the engine's cheap physical order, page by page. */
public interface TableScan {

    /**
     * The next rows after a cursor.
     *
     * @param table the table
     * @param after the last row already consumed
     * @param limit the most rows (a page may hold fewer even when more follow)
     * @return the rows in scan order; empty when nothing follows right now
     * @throws SQLException when reading fails
     */
    @NotNull List<SourceRow> read(@NotNull SourceTable table, @NotNull SourceCursor after, int limit)
            throws SQLException;

    /**
     * About how many rows follow a cursor, for progress only.
     *
     * @param table the table
     * @param after the last row already consumed
     * @return the estimate, never negative
     * @throws SQLException when reading fails
     */
    long remaining(@NotNull SourceTable table, @NotNull SourceCursor after) throws SQLException;

    /**
     * A stored cursor, checked against the table as it is now: the same cursor while its row ids still
     * mean what they meant, or one re-anchored by time when the table was renumbered.
     *
     * @param table  the table
     * @param cursor the cursor of an earlier run
     * @return where reading continues
     * @throws SQLException when reading fails
     */
    @NotNull SourceCursor anchor(@NotNull SourceTable table, @NotNull SourceCursor cursor) throws SQLException;
}
