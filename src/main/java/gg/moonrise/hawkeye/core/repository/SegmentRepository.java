package gg.moonrise.hawkeye.core.repository;

import gg.moonrise.hawkeye.core.sql.Dialect;
import gg.moonrise.hawkeye.core.sql.Sql;
import gg.moonrise.hawkeye.util.BlockPositions;
import gg.moonrise.moss.spring.SpringComponent;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The archive tables: {@code segment} (packed events of one world region, see {@link SegmentCodec})
 * and {@code segment_actor} (which actors appear in which segment, so a user lookup skips segments
 * without them). Candidate segments are found by region (the same chunk-column trick as events),
 * world, actor and id range; the events inside are then filtered with {@link EventFilter#matches}.
 */
@SpringComponent
public final class SegmentRepository {

    private static final String COLUMNS = "id, world, region, first_id, last_id, events, body";

    /**
     * Writes a segment and its actor index.
     *
     * @param connection a connection (inside the archiving transaction)
     * @param sql        the shard's SQL context
     * @param segment    the segment (its id is ignored)
     * @param actors     the actor ids that appear in it
     * @return the new segment id
     * @throws SQLException when an insert fails
     */
    public long insert(@NotNull Connection connection, @NotNull Sql sql, @NotNull SegmentRow segment,
            @NotNull Collection<Integer> actors) throws SQLException {
        String insert = "INSERT INTO " + sql.tables().segment() + " (world, region, first_id, last_id, events, body) "
                + "VALUES (" + Dialect.placeholders(6) + ")";
        long id;
        try (PreparedStatement statement = connection.prepareStatement(insert, Statement.RETURN_GENERATED_KEYS)) {
            bindBody(statement, segment);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("the segment insert returned no id");
                }
                id = keys.getLong(1);
            }
        }
        List<Integer> distinct = List.copyOf(new LinkedHashSet<>(actors));
        if (!distinct.isEmpty()) {
            String index = sql.dialect().insertIgnoringDuplicates(sql.tables().segmentActor(),
                    List.of("actor", "segment"), distinct.size());
            try (PreparedStatement statement = connection.prepareStatement(index)) {
                int parameter = 1;
                for (int actor : distinct) {
                    statement.setInt(parameter++, actor);
                    statement.setLong(parameter++, id);
                }
                statement.executeUpdate();
            }
        }
        return id;
    }

    /**
     * A page of candidate segments for a filter.
     *
     * @param connection  a connection
     * @param sql         the shard's SQL context
     * @param filter      the event filter (area, worlds, actors and id range narrow the segments)
     * @param eventCursor the event keyset bound of the lookup (0 for none): segments must hold events
     *                    beyond it in the reading direction
     * @param after       the last segment of the previous page, or {@code null} for the first page
     * @param limit       the most segments
     * @param newestFirst {@code true} to page by {@code last_id} descending, else by {@code first_id} ascending
     * @return the segments in reading order
     * @throws SQLException when the query fails
     */
    public @NotNull List<SegmentRow> candidates(@NotNull Connection connection, @NotNull Sql sql,
            @NotNull EventFilter filter, long eventCursor, @Nullable SegmentRow after,
            int limit, boolean newestFirst) throws SQLException {
        List<Object> parameters = new ArrayList<>();
        List<String> conditions = new ArrayList<>();
        conditions.add("last_id >= ? AND first_id <= ?");
        parameters.add(filter.minId());
        parameters.add(filter.maxId());
        String area = area(filter, parameters);
        if (!area.isEmpty()) {
            conditions.add(area);
        }
        if (!filter.actors().isEmpty()) {
            conditions.add("id IN (SELECT segment FROM " + sql.tables().segmentActor() + " WHERE actor IN ("
                    + Dialect.placeholders(filter.actors().size()) + "))");
            parameters.addAll(filter.actors());
        }
        if (eventCursor != 0) {
            conditions.add(newestFirst ? "first_id < ?" : "last_id > ?");
            parameters.add(eventCursor);
        }
        String order;
        if (newestFirst) {
            if (after != null) {
                conditions.add("(last_id < ? OR (last_id = ? AND id < ?))");
                parameters.add(after.lastId());
                parameters.add(after.lastId());
                parameters.add(after.id());
            }
            order = " ORDER BY last_id DESC, id DESC";
        } else {
            if (after != null) {
                conditions.add("(first_id > ? OR (first_id = ? AND id > ?))");
                parameters.add(after.firstId());
                parameters.add(after.firstId());
                parameters.add(after.id());
            }
            order = " ORDER BY first_id ASC, id ASC";
        }
        parameters.add(limit);
        BoundSql query = new BoundSql("SELECT " + COLUMNS + " FROM " + sql.tables().segment() + " WHERE "
                + String.join(" AND ", conditions) + order + " LIMIT ?", parameters);
        try (PreparedStatement statement = connection.prepareStatement(query.sql())) {
            query.bind(statement);
            return read(statement);
        }
    }

    /**
     * Segments holding events older than an id, for purging.
     *
     * @param connection a connection
     * @param sql        the shard's SQL context
     * @param belowId    the exclusive id bound
     * @param worlds     world ids, or empty for all
     * @param limit      the most segments
     * @return the oldest such segments first
     * @throws SQLException when the query fails
     */
    public @NotNull List<SegmentRow> olderThan(@NotNull Connection connection, @NotNull Sql sql, long belowId,
            @NotNull Collection<Integer> worlds, int limit) throws SQLException {
        List<Object> parameters = new ArrayList<>();
        parameters.add(belowId);
        String worldCondition = "";
        if (!worlds.isEmpty()) {
            worldCondition = " AND world IN (" + Dialect.placeholders(worlds.size()) + ")";
            parameters.addAll(worlds);
        }
        parameters.add(limit);
        BoundSql query = new BoundSql("SELECT " + COLUMNS + " FROM " + sql.tables().segment() + " WHERE first_id < ?"
                + worldCondition + " ORDER BY first_id LIMIT ?", parameters);
        try (PreparedStatement statement = connection.prepareStatement(query.sql())) {
            query.bind(statement);
            return read(statement);
        }
    }

    /**
     * Replaces a segment's events (after a flag change or a partial purge).
     *
     * @param connection a connection
     * @param sql        the shard's SQL context
     * @param segment    the segment with its new range, count and body
     * @throws SQLException when the update fails
     */
    public void update(@NotNull Connection connection, @NotNull Sql sql, @NotNull SegmentRow segment)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE " + sql.tables().segment()
                + " SET world = ?, region = ?, first_id = ?, last_id = ?, events = ?, body = ? WHERE id = ?")) {
            bindBody(statement, segment);
            statement.setLong(7, segment.id());
            statement.executeUpdate();
        }
    }

    /**
     * Deletes segments and their actor index rows.
     *
     * @param connection a connection
     * @param sql        the shard's SQL context
     * @param ids        segment ids
     * @return the number of segments deleted
     * @throws SQLException when a delete fails
     */
    public int delete(@NotNull Connection connection, @NotNull Sql sql, @NotNull Collection<Long> ids)
            throws SQLException {
        if (ids.isEmpty()) {
            return 0;
        }
        List<Long> values = List.copyOf(ids);
        String in = " IN (" + Dialect.placeholders(values.size()) + ")";
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM " + sql.tables().segmentActor()
                + " WHERE segment" + in)) {
            bindLongs(statement, values);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM " + sql.tables().segment()
                + " WHERE id" + in)) {
            bindLongs(statement, values);
            return statement.executeUpdate();
        }
    }

    /**
     * The largest id any segment holds (an index lookup on {@code last_id}).
     *
     * @param connection a connection
     * @param sql        the shard's SQL context
     * @return the id, or 0 when the archive is empty
     * @throws SQLException when the query fails
     */
    public long newestId(@NotNull Connection connection, @NotNull Sql sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT MAX(last_id) FROM "
                + sql.tables().segment());
                ResultSet rows = statement.executeQuery()) {
            return rows.next() ? rows.getLong(1) : 0;
        }
    }

    /**
     * The number of archived events and segments.
     *
     * @param connection a connection
     * @param sql        the shard's SQL context
     * @return {@code [events, segments]}
     * @throws SQLException when the query fails
     */
    public long @NotNull [] totals(@NotNull Connection connection, @NotNull Sql sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COALESCE(SUM(events), 0), COUNT(*) FROM "
                + sql.tables().segment());
             ResultSet rows = statement.executeQuery()) {
            rows.next();
            return new long[] {rows.getLong(1), rows.getLong(2)};
        }
    }

    private static @NotNull String area(@NotNull EventFilter filter, @NotNull List<Object> parameters) {
        if (!filter.positions().isEmpty()) {
            Set<RegionRange> ranges = new LinkedHashSet<>();
            for (EventFilter.WorldRange range : filter.positions()) {
                int fromRegionX = BlockPositions.chunkX(range.from()) >> BlockPositions.REGION_SHIFT;
                int toRegionX = BlockPositions.chunkX(range.to()) >> BlockPositions.REGION_SHIFT;
                int fromRegionZ = BlockPositions.chunkZ(range.from()) >> BlockPositions.REGION_SHIFT;
                int toRegionZ = BlockPositions.chunkZ(range.to()) >> BlockPositions.REGION_SHIFT;
                for (int regionX = fromRegionX; regionX <= toRegionX; regionX++) {
                    ranges.add(new RegionRange(range.world(), BlockPositions.regionKey(regionX, fromRegionZ),
                            BlockPositions.regionKey(regionX, toRegionZ)));
                }
            }
            StringJoiner terms = new StringJoiner(" OR ", "(", ")");
            for (RegionRange range : ranges) {
                terms.add("(world = ? AND region BETWEEN ? AND ?)");
                parameters.add(range.world());
                parameters.add(range.from());
                parameters.add(range.to());
            }
            return terms.toString();
        }
        if (!filter.worlds().isEmpty()) {
            parameters.addAll(filter.worlds());
            return "world IN (" + Dialect.placeholders(filter.worlds().size()) + ")";
        }
        return "";
    }

    private static void bindBody(@NotNull PreparedStatement statement, @NotNull SegmentRow segment)
            throws SQLException {
        statement.setInt(1, segment.world());
        statement.setLong(2, segment.region());
        statement.setLong(3, segment.firstId());
        statement.setLong(4, segment.lastId());
        statement.setInt(5, segment.events());
        statement.setBytes(6, segment.body());
    }

    private static void bindLongs(@NotNull PreparedStatement statement, @NotNull List<Long> values)
            throws SQLException {
        for (int i = 0; i < values.size(); i++) {
            statement.setLong(i + 1, values.get(i));
        }
    }

    private static @NotNull List<SegmentRow> read(@NotNull PreparedStatement statement) throws SQLException {
        List<SegmentRow> segments = new ArrayList<>();
        try (ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                segments.add(new SegmentRow(rows.getLong(1), rows.getInt(2), rows.getLong(3), rows.getLong(4),
                        rows.getLong(5), rows.getInt(6), rows.getBytes(7)));
            }
        }
        return segments;
    }

    private record RegionRange(int world, long from, long to) {
    }
}
