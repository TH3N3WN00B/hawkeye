package gg.moonrise.hawkeye.core.repository;

import gg.moonrise.hawkeye.core.sql.Sql;
import gg.moonrise.moss.spring.SpringComponent;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.jetbrains.annotations.NotNull;

/**
 * Reads and writes the {@code event} table on a borrowed connection. Stateless: the caller owns the
 * connection and the transaction.
 */
@SpringComponent
public final class EventRepository {

    /** Rows per multi-row insert (10 columns each, well inside every engine's parameter limit). */
    public static final int ROWS_PER_STATEMENT = 500;

    /** Ids per {@code IN} list of a flag update. */
    public static final int IDS_PER_STATEMENT = 500;

    /**
     * Inserts events, skipping ids that already exist (so retrying a batch whose commit result was lost
     * is harmless).
     *
     * @param connection the connection
     * @param sql        the shard's SQL context
     * @param rows       the rows
     * @return the number of rows inserted
     * @throws SQLException when a statement fails
     */
    public int insert(@NotNull Connection connection, @NotNull Sql sql, @NotNull List<EventRow> rows)
            throws SQLException {
        int inserted = 0;
        for (int from = 0; from < rows.size(); from += ROWS_PER_STATEMENT) {
            List<EventRow> chunk = rows.subList(from, Math.min(rows.size(), from + ROWS_PER_STATEMENT));
            String statementSql = sql.dialect().insertIgnoringDuplicates(sql.tables().event(), EventSql.COLUMNS,
                    chunk.size());
            try (PreparedStatement statement = connection.prepareStatement(statementSql)) {
                int index = 1;
                for (EventRow row : chunk) {
                    index = bind(statement, index, row);
                }
                inserted += statement.executeUpdate();
            }
        }
        return inserted;
    }

    /**
     * Reads a page of events.
     *
     * @param connection  the connection
     * @param sql         the shard's SQL context
     * @param filter      the filter
     * @param cursor      the exclusive keyset bound, 0 for the first page
     * @param limit       the most rows
     * @param newestFirst the order
     * @return the rows in that order
     * @throws SQLException when the query fails
     */
    public @NotNull List<EventRow> find(@NotNull Connection connection, @NotNull Sql sql, @NotNull EventFilter filter,
            long cursor, int limit, boolean newestFirst) throws SQLException {
        BoundSql query = EventSql.select(sql.tables(), filter, cursor, limit, newestFirst);
        try (PreparedStatement statement = connection.prepareStatement(query.sql())) {
            query.bind(statement);
            try (ResultSet rows = statement.executeQuery()) {
                List<EventRow> out = new ArrayList<>(Math.min(limit, 1024));
                while (rows.next()) {
                    out.add(read(rows));
                }
                return out;
            }
        }
    }

    /**
     * Counts matching events.
     *
     * @param connection the connection
     * @param sql        the shard's SQL context
     * @param filter     the filter
     * @return the count
     * @throws SQLException when the query fails
     */
    public long count(@NotNull Connection connection, @NotNull Sql sql, @NotNull EventFilter filter)
            throws SQLException {
        BoundSql query = EventSql.count(sql.tables(), filter);
        try (PreparedStatement statement = connection.prepareStatement(query.sql())) {
            query.bind(statement);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong(1) : 0;
            }
        }
    }

    /**
     * Sets or clears the rolled-back flag.
     *
     * @param connection the connection
     * @param sql        the shard's SQL context
     * @param ids        the event ids
     * @param rolledBack the new value
     * @return the number of rows updated
     * @throws SQLException when a statement fails
     */
    public int setRolledBack(@NotNull Connection connection, @NotNull Sql sql, @NotNull Collection<Long> ids,
            boolean rolledBack) throws SQLException {
        List<Long> all = List.copyOf(ids);
        int updated = 0;
        for (int from = 0; from < all.size(); from += IDS_PER_STATEMENT) {
            BoundSql update = EventSql.setRolledBack(sql.tables(),
                    all.subList(from, Math.min(all.size(), from + IDS_PER_STATEMENT)), rolledBack);
            try (PreparedStatement statement = connection.prepareStatement(update.sql())) {
                update.bind(statement);
                updated += statement.executeUpdate();
            }
        }
        return updated;
    }

    /**
     * Deletes up to {@code limit} of the oldest events below an id.
     *
     * @param connection the connection
     * @param sql        the shard's SQL context
     * @param belowId    the exclusive upper id bound
     * @param worlds     world ids to purge, or empty for every world
     * @param limit      the most rows
     * @return the number of rows deleted
     * @throws SQLException when the statement fails
     */
    public int deleteBatch(@NotNull Connection connection, @NotNull Sql sql, long belowId,
            @NotNull Collection<Integer> worlds, int limit) throws SQLException {
        BoundSql delete = EventSql.deleteBatch(sql.dialect(), sql.tables(), belowId, worlds, limit);
        try (PreparedStatement statement = connection.prepareStatement(delete.sql())) {
            delete.bind(statement);
            return statement.executeUpdate();
        }
    }

    private static int bind(@NotNull PreparedStatement statement, int start, @NotNull EventRow row)
            throws SQLException {
        int index = start;
        statement.setLong(index++, row.id());
        statement.setInt(index++, row.world());
        statement.setLong(index++, row.position());
        statement.setInt(index++, row.actor());
        statement.setInt(index++, row.action());
        statement.setInt(index++, row.subject());
        setOptionalInt(statement, index++, row.secondary());
        setOptionalInt(statement, index++, row.amount());
        statement.setInt(index++, row.flags());
        if (row.data() == null) {
            statement.setNull(index++, Types.BINARY);
        } else {
            statement.setBytes(index++, row.data());
        }
        return index;
    }

    private static void setOptionalInt(@NotNull PreparedStatement statement, int index, int value)
            throws SQLException {
        if (value == 0) {
            statement.setNull(index, Types.INTEGER);
        } else {
            statement.setInt(index, value);
        }
    }

    private static @NotNull EventRow read(@NotNull ResultSet rows) throws SQLException {
        return new EventRow(rows.getLong(1), rows.getInt(2), rows.getLong(3), rows.getInt(4), rows.getInt(5),
                rows.getInt(6), rows.getInt(7), rows.getInt(8), rows.getInt(9), rows.getBytes(10));
    }
}
