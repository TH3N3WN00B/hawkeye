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
 * Time order (ClickHouse): keyset pages on {@code (time, rowid)} with {@code LIMIT}, bounded to
 * {@link #WINDOW_SECONDS} above the cursor's time so ClickHouse prunes partitions instead of sorting the
 * whole table. ClickHouse reserves rowids in blocks per writer, so rowid order is not time order there.
 * When a window holds nothing, the next time beyond it is looked up and reading continues from there.
 */
public final class TimeScan implements TableScan {

    /** How far above the cursor a page looks: about a month. */
    public static final long WINDOW_SECONDS = 31L * 24 * 60 * 60;

    private final @NotNull Connection connection;
    private final @NotNull SourceSchema schema;
    private final @NotNull SourceRowMapper mapper;

    /**
     * A time scan.
     *
     * @param connection the source connection
     * @param schema     its schema
     * @param mapper     its row layout
     */
    public TimeScan(@NotNull Connection connection, @NotNull SourceSchema schema, @NotNull SourceRowMapper mapper) {
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
        String time = schema.dialect().quote("time");
        String page = "SELECT " + mapper.projection(table) + " FROM " + name + " WHERE " + time + " >= ? AND " + time
                + " < ? AND (" + time + " > ? OR rowid > ?) ORDER BY " + time + ", rowid LIMIT ?";
        SourceCursor cursor = after;
        while (true) {
            List<SourceRow> rows = page(table, page, cursor, limit);
            if (!rows.isEmpty()) {
                return rows;
            }
            OptionalLong next = next(name, time, cursor.time() + WINDOW_SECONDS);
            if (next.isEmpty()) {
                return List.of();
            }
            // Just before the next row's time: every rowid at that time follows.
            cursor = new SourceCursor(next.getAsLong() - 1, Long.MAX_VALUE);
        }
    }

    @Override
    public long remaining(@NotNull SourceTable table, @NotNull SourceCursor after) throws SQLException {
        String time = schema.dialect().quote("time");
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM "
                + schema.table(table.tableName()) + " WHERE " + time + " > ? OR (" + time + " = ? AND rowid > ?)")) {
            statement.setLong(1, after.time());
            statement.setLong(2, after.time());
            statement.setLong(3, after.rowId());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Math.max(0, rows.getLong(1)) : 0;
            }
        }
    }

    /** A time-order cursor does not depend on row ids alone: it is kept as it is. */
    @Override
    public @NotNull SourceCursor anchor(@NotNull SourceTable table, @NotNull SourceCursor cursor) {
        return cursor;
    }

    private @NotNull List<SourceRow> page(@NotNull SourceTable table, @NotNull String sql,
            @NotNull SourceCursor after, int limit) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, after.time());
            statement.setLong(2, after.time() + WINDOW_SECONDS);
            statement.setLong(3, after.time());
            statement.setLong(4, after.rowId());
            statement.setInt(5, limit);
            try (ResultSet rows = statement.executeQuery()) {
                List<SourceRow> out = new ArrayList<>();
                while (rows.next()) {
                    out.add(mapper.map(table, rows));
                }
                return out;
            }
        }
    }

    private @NotNull OptionalLong next(@NotNull String table, @NotNull String time, long from) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT " + time + " FROM " + table
                + " WHERE " + time + " >= ? ORDER BY " + time + " LIMIT 1")) {
            statement.setLong(1, from);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? OptionalLong.of(rows.getLong(1)) : OptionalLong.empty();
            }
        }
    }
}
