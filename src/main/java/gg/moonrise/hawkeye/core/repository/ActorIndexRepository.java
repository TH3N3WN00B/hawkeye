package gg.moonrise.hawkeye.core.repository;

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
 * The actor table of the segment index: {@code actor_segment}, keyed by the actor dictionary id, with
 * the largest segment id the row mentions ({@code newest}, indexed for trimming) and the ids of the
 * segments that hold rows of the actor as raw {@code IdRuns} bytes (rows are a few bytes to a few
 * hundred, so no compression frame). Rows are read and written in batches: one statement for many keys.
 */
@SpringComponent
public final class ActorIndexRepository {

    /** Keys per {@code IN} list. */
    public static final int READ_BATCH = 500;

    /** Rows per multi-row upsert. */
    public static final int WRITE_BATCH = 200;

    /**
     * One row to write.
     *
     * @param actor  the actor id
     * @param newest the largest segment id the row mentions
     * @param body   the encoded ids
     */
    public record Row(int actor, long newest, byte @NotNull [] body) {
    }

    /**
     * The bodies of some actors' rows.
     *
     * @param connection a connection
     * @param sql        the shard's SQL context
     * @param actors     the actor ids
     * @return the bodies of the rows that exist, by actor id
     * @throws SQLException when a query fails
     */
    public @NotNull Map<Integer, byte[]> read(@NotNull Connection connection, @NotNull Sql sql,
            @NotNull Collection<Integer> actors) throws SQLException {
        Map<Integer, byte[]> out = new HashMap<>();
        List<Integer> all = List.copyOf(actors);
        for (int from = 0; from < all.size(); from += READ_BATCH) {
            BoundSql query = readQuery(sql, all.subList(from, Math.min(all.size(), from + READ_BATCH)));
            try (PreparedStatement statement = connection.prepareStatement(query.sql())) {
                query.bind(statement);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        out.put(rows.getInt(1), rows.getBytes(2));
                    }
                }
            }
        }
        return out;
    }

    /**
     * The statement of one batch of {@link #read} (also read by the query-plan tests).
     *
     * @param sql    the shard's SQL context
     * @param actors the actor ids, at least one
     * @return the statement
     */
    public static @NotNull BoundSql readQuery(@NotNull Sql sql, @NotNull List<Integer> actors) {
        return new BoundSql("SELECT actor, body FROM " + sql.tables().actorSegment() + " WHERE actor IN ("
                + Dialect.placeholders(actors.size()) + ")", new ArrayList<>(actors));
    }

    /**
     * Inserts or replaces rows.
     *
     * @param connection a connection inside the transaction that holds the index lock
     * @param sql        the shard's SQL context
     * @param rows       the rows
     * @throws SQLException when a statement fails
     */
    public void write(@NotNull Connection connection, @NotNull Sql sql, @NotNull List<Row> rows) throws SQLException {
        for (int from = 0; from < rows.size(); from += WRITE_BATCH) {
            List<Row> batch = rows.subList(from, Math.min(rows.size(), from + WRITE_BATCH));
            String upsert = sql.dialect().upsert(sql.tables().actorSegment(), List.of("actor", "newest", "body"),
                    "actor", List.of("newest", "body"), batch.size());
            try (PreparedStatement statement = connection.prepareStatement(upsert)) {
                int parameter = 1;
                for (Row row : batch) {
                    statement.setInt(parameter++, row.actor());
                    statement.setLong(parameter++, row.newest());
                    statement.setBytes(parameter++, row.body());
                }
                statement.executeUpdate();
            }
        }
    }

    /**
     * Deletes some actors' rows.
     *
     * @param connection a connection inside the transaction that holds the index lock
     * @param sql        the shard's SQL context
     * @param actors     the actor ids
     * @throws SQLException when a statement fails
     */
    public void delete(@NotNull Connection connection, @NotNull Sql sql, @NotNull Collection<Integer> actors)
            throws SQLException {
        List<Integer> all = List.copyOf(actors);
        for (int from = 0; from < all.size(); from += READ_BATCH) {
            List<Integer> batch = all.subList(from, Math.min(all.size(), from + READ_BATCH));
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM "
                    + sql.tables().actorSegment() + " WHERE actor IN (" + Dialect.placeholders(batch.size()) + ")")) {
                for (int i = 0; i < batch.size(); i++) {
                    statement.setInt(i + 1, batch.get(i));
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
     * @return the number of rows deleted
     * @throws SQLException when the statement fails
     */
    public int deleteOlderThan(@NotNull Connection connection, @NotNull Sql sql, long oldest) throws SQLException {
        BoundSql query = deleteOlderQuery(sql, oldest);
        try (PreparedStatement statement = connection.prepareStatement(query.sql())) {
            query.bind(statement);
            return statement.executeUpdate();
        }
    }

    /**
     * The statement of {@link #deleteOlderThan} (also read by the query-plan tests).
     *
     * @param sql    the shard's SQL context
     * @param oldest the oldest stored segment id
     * @return the statement
     */
    public static @NotNull BoundSql deleteOlderQuery(@NotNull Sql sql, long oldest) {
        return new BoundSql("DELETE FROM " + sql.tables().actorSegment() + " WHERE newest < ?", List.of(oldest));
    }
}
