package gg.moonrise.hawkeye.core.repository.coreprotect;

import gg.moonrise.hawkeye.entity.importer.SourceCursor;
import gg.moonrise.hawkeye.entity.importer.SourceRow;
import gg.moonrise.hawkeye.entity.importer.SourceTable;
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
        try (PreparedStatement statement = connection.prepareStatement("SELECT MIN(rowid) FROM " + table
                + " WHERE rowid > ?")) {
            statement.setLong(1, after);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return OptionalLong.empty();
                }
                long next = rows.getLong(1);
                return rows.wasNull() ? OptionalLong.empty() : OptionalLong.of(next);
            }
        }
    }
}
