package gg.moonrise.hawkeye.core.repository.coreprotect.reader;

import gg.moonrise.hawkeye.core.repository.coreprotect.schema.SourceDialect;
import gg.moonrise.hawkeye.core.repository.coreprotect.schema.SourceSchema;
import gg.moonrise.hawkeye.entity.importer.source.SourceCursor;
import gg.moonrise.hawkeye.entity.importer.source.SourceRow;
import gg.moonrise.hawkeye.entity.importer.source.SourceTable;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import org.jetbrains.annotations.NotNull;

/**
 * Rowid order (SQLite, MySQL, DuckDB): windows of {@code limit} row ids,
 * {@code WHERE rowid > ? AND rowid <= ? ORDER BY rowid}, never {@code LIMIT} keyset pages. A window is a
 * range seek on SQLite's and MySQL's primary key and a zone-map skip on DuckDB, so every page costs the
 * same however far the table has been read. An empty window (rows deleted by a purge) is jumped with
 * {@code SELECT MIN(rowid) ... WHERE rowid > ?}. The remaining count is {@code MAX(rowid)} minus the
 * cursor: never a full count.
 *
 * <p>A stored cursor is checked before a run continues from it ({@link #anchor}): CoreProtect's
 * {@code /co purge} on SQLite rebuilds every table but {@code block} with {@code INSERT ... SELECT},
 * renumbering its rows from 1 in the same order, so an old rowid then names a later row or none. Such a
 * cursor is re-anchored by time: to the last row stamped at or before the cursor's time.
 */
public final class RowidScan implements TableScan {

    private final @NotNull Connection connection;
    private final @NotNull SourceSchema schema;
    private final @NotNull SourceRowMapper mapper;

    /**
     * A rowid scan.
     *
     * @param connection the source connection
     * @param schema     its schema
     * @param mapper     its row layout
     */
    public RowidScan(@NotNull Connection connection, @NotNull SourceSchema schema, @NotNull SourceRowMapper mapper) {
        this.connection = connection;
        this.schema = schema;
        this.mapper = mapper;
    }

    @Override
    public @NotNull List<SourceRow> read(@NotNull SourceTable table, @NotNull SourceCursor after, int limit)
            throws SQLException {
        if (limit <= 0) {
            return List.of();
        }
        String name = schema.table(table.tableName());
        String window = "SELECT " + mapper.projection(table) + " FROM " + name
                + " WHERE rowid > ? AND rowid <= ? ORDER BY rowid";
        long from = after.rowId();
        while (true) {
            List<SourceRow> rows = window(table, window, from, from > Long.MAX_VALUE - limit ? Long.MAX_VALUE
                    : from + limit);
            if (!rows.isEmpty()) {
                return rows;
            }
            OptionalLong next = next(name, from);
            if (next.isEmpty()) {
                return List.of();
            }
            from = next.getAsLong() - 1;
        }
    }

    @Override
    public long remaining(@NotNull SourceTable table, @NotNull SourceCursor after) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT MAX(rowid) FROM "
                + schema.table(table.tableName()));
                ResultSet rows = statement.executeQuery()) {
            return rows.next() ? Math.max(0, rows.getLong(1) - after.rowId()) : 0;
        }
    }

    /**
     * Keeps the cursor while its row is still there with the same time. Its row with another time means
     * the table was renumbered, as does a missing row on SQLite for any table but {@code block}; a missing
     * row anywhere else was deleted by a purge that keeps row ids, and reading simply continues after it.
     * A renumbered table continues after the last row stamped at or before the cursor's time.
     */
    @Override
    public @NotNull SourceCursor anchor(@NotNull SourceTable table, @NotNull SourceCursor cursor)
            throws SQLException {
        if (cursor.equals(SourceCursor.START)) {
            return cursor;
        }
        String name = schema.table(table.tableName());
        String time = schema.dialect().quote("time");
        OptionalLong stored = single("SELECT " + time + " FROM " + name + " WHERE rowid = ?", cursor.rowId());
        boolean renumbered = stored.isPresent() ? stored.getAsLong() != cursor.time()
                : schema.dialect() == SourceDialect.SQLITE && table != SourceTable.BLOCK;
        if (!renumbered) {
            return cursor;
        }
        OptionalLong last = single("SELECT MAX(rowid) FROM " + name + " WHERE " + time + " <= ?", cursor.time());
        return new SourceCursor(cursor.time(), last.orElse(0));
    }

    private @NotNull List<SourceRow> window(@NotNull SourceTable table, @NotNull String sql, long from, long to)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, from);
            statement.setLong(2, to);
            try (ResultSet rows = statement.executeQuery()) {
                List<SourceRow> out = new ArrayList<>();
                while (rows.next()) {
                    out.add(mapper.map(table, rows));
                }
                return out;
            }
        }
    }

    private @NotNull OptionalLong next(@NotNull String table, long after) throws SQLException {
        return single("SELECT MIN(rowid) FROM " + table + " WHERE rowid > ?", after);
    }

    /** The first column of the first row of a query with one parameter; empty without a row or for NULL. */
    private @NotNull OptionalLong single(@NotNull String sql, long parameter) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, parameter);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return OptionalLong.empty();
                }
                long value = rows.getLong(1);
                return rows.wasNull() ? OptionalLong.empty() : OptionalLong.of(value);
            }
        }
    }
}
