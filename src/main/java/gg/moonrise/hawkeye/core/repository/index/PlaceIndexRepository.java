package gg.moonrise.hawkeye.core.repository.index;

import gg.moonrise.hawkeye.core.repository.event.BoundSql;
import gg.moonrise.hawkeye.core.sql.Dialect;
import gg.moonrise.hawkeye.core.sql.Sql;
import gg.moonrise.moss.spring.SpringComponent;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.NotNull;

/**
 * The place tables of the segment index ({@code core.storage.index.place} holds the row codecs):
 * {@code region_segment} keyed by {@code (world, region)} and {@code block_segment} keyed by
 * {@code (world, chunk)}, both with the largest segment id they mention ({@code newest}, indexed for
 * trimming) and a compressed body. The state row is {@link SegmentIndexRepository}'s. Rows are read and
 * written in batches: one statement for many keys.
 */
@SpringComponent
public final class PlaceIndexRepository {

    /** Keys per {@code IN} list. */
    public static final int READ_BATCH = 500;

    /** Rows per multi-row upsert. */
    public static final int WRITE_BATCH = 100;

    /** Which of the two keyed tables. */
    public enum Kind {
        /** {@code region_segment}, keyed by region. */
        REGION("region"),
        /** {@code block_segment}, keyed by chunk. */
        CHUNK("chunk");

        private final String keyColumn;

        private Kind(@NotNull String keyColumn) {
            this.keyColumn = keyColumn;
        }

        /**
         * The key column.
         *
         * @return the column name
         */
        public @NotNull String keyColumn() {
            return keyColumn;
        }

        /**
         * The table.
         *
         * @param sql the shard's SQL context
         * @return the table name
         */
        public @NotNull String table(@NotNull Sql sql) {
            return this == REGION ? sql.tables().regionSegment() : sql.tables().blockSegment();
        }
    }

    /**
     * One row to write.
     *
     * @param key    the region or chunk key
     * @param newest the largest segment id the row mentions
     * @param body   the encoded body
     */
    public record Row(long key, long newest, byte @NotNull [] body) {
    }

    /**
     * The bodies of some rows of one world.
     *
     * @param connection a connection
     * @param sql        the shard's SQL context
     * @param kind       the table
     * @param world      the world id
     * @param keys       the keys
     * @return the bodies of the rows that exist, by key
     * @throws SQLException when a query fails
     */
    public @NotNull Map<Long, byte[]> read(@NotNull Connection connection, @NotNull Sql sql, @NotNull Kind kind,
            int world, @NotNull Collection<Long> keys) throws SQLException {
        Map<Long, byte[]> out = new HashMap<>();
        List<Long> all = List.copyOf(keys);
        for (int from = 0; from < all.size(); from += READ_BATCH) {
            BoundSql query = readQuery(sql, kind, world, all.subList(from, Math.min(all.size(), from + READ_BATCH)));
            try (PreparedStatement statement = connection.prepareStatement(query.sql())) {
                query.bind(statement);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        out.put(rows.getLong(1), rows.getBytes(2));
                    }
                }
            }
        }
        return out;
    }

    /**
     * The statement of one batch of {@link #read} (also read by the query-plan tests).
     *
     * @param sql   the shard's SQL context
     * @param kind  the table
     * @param world the world id
     * @param keys  the keys, at least one
     * @return the statement
     */
    public static @NotNull BoundSql readQuery(@NotNull Sql sql, @NotNull Kind kind, int world,
            @NotNull List<Long> keys) {
        List<Object> parameters = new ArrayList<>(keys.size() + 1);
        parameters.add(world);
        parameters.addAll(keys);
        return new BoundSql("SELECT " + kind.keyColumn() + ", body FROM " + kind.table(sql) + " WHERE world = ? AND "
                + kind.keyColumn() + " IN (" + Dialect.placeholders(keys.size()) + ")", parameters);
    }

    /**
     * Inserts or replaces rows of one world.
     *
     * @param connection a connection inside the transaction that holds the index lock
     * @param sql        the shard's SQL context
     * @param kind       the table
     * @param world      the world id
     * @param rows       the rows
     * @throws SQLException when a statement fails
     */
    public void write(@NotNull Connection connection, @NotNull Sql sql, @NotNull Kind kind, int world,
            @NotNull List<Row> rows) throws SQLException {
        for (int from = 0; from < rows.size(); from += WRITE_BATCH) {
            List<Row> batch = rows.subList(from, Math.min(rows.size(), from + WRITE_BATCH));
            String upsert = sql.dialect().upsert(kind.table(sql), List.of("world", kind.keyColumn(), "newest", "body"),
                    "world, " + kind.keyColumn(), List.of("newest", "body"), batch.size());
            try (PreparedStatement statement = connection.prepareStatement(upsert)) {
                int parameter = 1;
                for (Row row : batch) {
                    statement.setInt(parameter++, world);
                    statement.setLong(parameter++, row.key());
                    statement.setLong(parameter++, row.newest());
                    statement.setBytes(parameter++, row.body());
                }
                statement.executeUpdate();
            }
        }
    }

    /**
     * Deletes rows of one world.
     *
     * @param connection a connection inside the transaction that holds the index lock
     * @param sql        the shard's SQL context
     * @param kind       the table
     * @param world      the world id
     * @param keys       the keys
     * @throws SQLException when a statement fails
     */
    public void delete(@NotNull Connection connection, @NotNull Sql sql, @NotNull Kind kind, int world,
            @NotNull Collection<Long> keys) throws SQLException {
        List<Long> all = List.copyOf(keys);
        for (int from = 0; from < all.size(); from += READ_BATCH) {
            List<Long> batch = all.subList(from, Math.min(all.size(), from + READ_BATCH));
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM " + kind.table(sql)
                    + " WHERE world = ? AND " + kind.keyColumn() + " IN (" + Dialect.placeholders(batch.size()) + ")")) {
                statement.setInt(1, world);
                for (int i = 0; i < batch.size(); i++) {
                    statement.setLong(i + 2, batch.get(i));
                }
                statement.executeUpdate();
            }
        }
    }

    /**
     * Deletes the rows that mention no segment at or above a bound (an index range on {@code newest}).
     *
     * @param connection a connection inside the transaction that holds the index lock
     * @param sql        the shard's SQL context
     * @param oldest     the oldest stored segment id
     * @return the number of rows deleted from both tables
     * @throws SQLException when a statement fails
     */
    public int deleteOlderThan(@NotNull Connection connection, @NotNull Sql sql, long oldest) throws SQLException {
        int deleted = 0;
        for (Kind kind : Kind.values()) {
            try (PreparedStatement statement = connection.prepareStatement(deleteOlderQuery(sql, kind, oldest).sql())) {
                statement.setLong(1, oldest);
                deleted += statement.executeUpdate();
            }
        }
        return deleted;
    }

    /**
     * The statement of {@link #deleteOlderThan} for one table (also read by the query-plan tests).
     *
     * @param sql    the shard's SQL context
     * @param kind   the table
     * @param oldest the oldest stored segment id
     * @return the statement
     */
    public static @NotNull BoundSql deleteOlderQuery(@NotNull Sql sql, @NotNull Kind kind, long oldest) {
        return new BoundSql("DELETE FROM " + kind.table(sql) + " WHERE newest < ?", List.of(oldest));
    }
}
