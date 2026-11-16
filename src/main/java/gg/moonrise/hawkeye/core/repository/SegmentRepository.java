package gg.moonrise.hawkeye.core.repository;

import gg.moonrise.hawkeye.core.repository.segment.ByteRange;
import gg.moonrise.hawkeye.core.repository.segment.PackedSegment;
import gg.moonrise.hawkeye.core.sql.Dialect;
import gg.moonrise.hawkeye.core.sql.Sql;
import gg.moonrise.moss.spring.SpringComponent;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The archive tables: {@code segment} (one immutable header and body of archive format 2 each, see
 * {@code segment.Segments}) and {@code segment_flag} (the rolled-back rows of a segment as
 * {@link FlagOrdinals}). Candidates carry the header and the flags, never the body; the body is read
 * by byte range, so a lookup about one place transfers the shared frames and one or two units.
 */
@SpringComponent
public final class SegmentRepository {

    private static final String CANDIDATE_COLUMNS = "s.id, s.first_id, s.last_id, s.events, s.header, f.ordinals";

    /**
     * Writes a segment, and its flag row when some of its rows are rolled back.
     *
     * @param connection a connection (inside the archiving transaction)
     * @param sql        the shard's SQL context
     * @param segment    the encoded segment
     * @return the new segment id
     * @throws SQLException when an insert fails
     */
    public long insert(@NotNull Connection connection, @NotNull Sql sql, @NotNull PackedSegment segment)
            throws SQLException {
        String insert = "INSERT INTO " + sql.tables().segment() + " (first_id, last_id, events, header, body) "
                + "VALUES (" + Dialect.placeholders(5) + ")";
        long id;
        try (PreparedStatement statement = connection.prepareStatement(insert, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, segment.firstId());
            statement.setLong(2, segment.lastId());
            statement.setInt(3, segment.events());
            statement.setBytes(4, segment.header());
            statement.setBytes(5, segment.body());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("the segment insert returned no id");
                }
                id = keys.getLong(1);
            }
        }
        if (segment.rolledBack().length > 0) {
            writeFlags(connection, sql, id, segment.rolledBack());
        }
        return id;
    }

    /**
     * A page of candidate segments for a filter: those whose id range overlaps the filter's and holds
     * ids beyond the event cursor, in reading order.
     *
     * @param connection  a connection
     * @param sql         the shard's SQL context
     * @param filter      the event filter (only its id range narrows the rows here; the header does the rest)
     * @param eventCursor the event keyset bound of the lookup (0 for none)
     * @param after       the last segment of the previous page, or {@code null} for the first page
     * @param limit       the most segments
     * @param newestFirst {@code true} to page by {@code last_id} descending, else by {@code first_id} ascending
     * @return the segments in reading order
     * @throws SQLException when the query fails
     */
    public @NotNull List<SegmentRow> candidates(@NotNull Connection connection, @NotNull Sql sql,
            @NotNull EventFilter filter, long eventCursor, @Nullable SegmentRow after, int limit, boolean newestFirst)
            throws SQLException {
        return select(connection, candidateQuery(sql, filter, eventCursor, after, limit, newestFirst));
    }

    /**
     * The statement of {@link #candidates} (also read by the query-plan tests).
     *
     * @param sql         the shard's SQL context
     * @param filter      the event filter
     * @param eventCursor the event keyset bound (0 for none)
     * @param after       the last segment of the previous page, or {@code null}
     * @param limit       the most segments
     * @param newestFirst the reading order
     * @return the statement
     */
    public static @NotNull BoundSql candidateQuery(@NotNull Sql sql, @NotNull EventFilter filter, long eventCursor,
            @Nullable SegmentRow after, int limit, boolean newestFirst) {
        List<Object> parameters = new ArrayList<>();
        List<String> conditions = new ArrayList<>();
        conditions.add("s.last_id >= ? AND s.first_id <= ?");
        parameters.add(filter.minId());
        parameters.add(filter.maxId());
        if (eventCursor != 0) {
            conditions.add(newestFirst ? "s.first_id < ?" : "s.last_id > ?");
            parameters.add(eventCursor);
        }
        String order;
        if (newestFirst) {
            if (after != null) {
                // The first term bounds the index walk; an OR alone would start every page at the newest segment.
                conditions.add("s.last_id <= ? AND (s.last_id < ? OR s.id < ?)");
                parameters.add(after.lastId());
                parameters.add(after.lastId());
                parameters.add(after.id());
            }
            order = " ORDER BY s.last_id DESC, s.id DESC";
        } else {
            if (after != null) {
                conditions.add("s.first_id >= ? AND (s.first_id > ? OR s.id > ?)");
                parameters.add(after.firstId());
                parameters.add(after.firstId());
                parameters.add(after.id());
            }
            order = " ORDER BY s.first_id ASC, s.id ASC";
        }
        parameters.add(limit);
        return query(sql, String.join(" AND ", conditions) + order + " LIMIT ?", parameters);
    }

    /**
     * Segments holding events older than an id, for purging, oldest first.
     *
     * @param connection a connection
     * @param sql        the shard's SQL context
     * @param belowId    the exclusive id bound
     * @param after      the last segment of the previous page, or {@code null} for the first page
     * @param limit      the most segments
     * @return the segments by {@code first_id}, then id
     * @throws SQLException when the query fails
     */
    public @NotNull List<SegmentRow> olderThan(@NotNull Connection connection, @NotNull Sql sql, long belowId,
            @Nullable SegmentRow after, int limit) throws SQLException {
        List<Object> parameters = new ArrayList<>();
        parameters.add(belowId);
        String keyset = "";
        if (after != null) {
            keyset = " AND (s.first_id > ? OR (s.first_id = ? AND s.id > ?))";
            parameters.add(after.firstId());
            parameters.add(after.firstId());
            parameters.add(after.id());
        }
        parameters.add(limit);
        return select(connection, query(sql, "s.first_id < ?" + keyset + " ORDER BY s.first_id ASC, s.id ASC LIMIT ?",
                parameters));
    }

    /**
     * Reads byte ranges of a segment body in one statement. Ranges that touch or overlap are fetched as
     * one and split again.
     *
     * @param connection a connection
     * @param sql        the shard's SQL context
     * @param segment    the segment id
     * @param ranges     the ranges, each inside the body
     * @return the bytes of each range, in the order given
     * @throws SQLException when the query fails, the segment is gone or the body is shorter than a range
     */
    public @NotNull List<byte[]> readRanges(@NotNull Connection connection, @NotNull Sql sql, long segment,
            @NotNull List<ByteRange> ranges) throws SQLException {
        if (ranges.isEmpty()) {
            return List.of();
        }
        List<ByteRange> fetched = merge(ranges);
        StringJoiner columns = new StringJoiner(", ");
        fetched.forEach(range -> columns.add("substr(body, ?, ?)"));
        byte[][] slices = new byte[fetched.size()][];
        try (PreparedStatement statement = connection.prepareStatement("SELECT " + columns + " FROM "
                + sql.tables().segment() + " WHERE id = ?")) {
            int parameter = 1;
            for (ByteRange range : fetched) {
                statement.setInt(parameter++, range.offset() + 1);
                statement.setInt(parameter++, range.length());
            }
            statement.setLong(parameter, segment);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("segment " + segment + " does not exist");
                }
                for (int i = 0; i < slices.length; i++) {
                    byte[] bytes = rows.getBytes(i + 1);
                    if (bytes == null || bytes.length != fetched.get(i).length()) {
                        throw new SQLException("segment " + segment + " is shorter than its header says");
                    }
                    slices[i] = bytes;
                }
            }
        }
        List<byte[]> out = new ArrayList<>(ranges.size());
        for (ByteRange range : ranges) {
            int index = containing(fetched, range);
            ByteRange slice = fetched.get(index);
            int from = range.offset() - slice.offset();
            out.add(Arrays.copyOfRange(slices[index], from, from + range.length()));
        }
        return out;
    }

    /**
     * A segment's rolled-back ordinals, locking the segment row against a concurrent flag change or
     * rewrite until the transaction ends (MySQL and PostgreSQL; SQLite has a single writer anyway).
     *
     * @param connection a connection inside a transaction
     * @param sql        the shard's SQL context
     * @param segment    the segment id
     * @return the ordinals, ascending; empty when the segment no longer exists
     * @throws SQLException when the query fails
     */
    public @NotNull Optional<int[]> flagsForUpdate(@NotNull Connection connection, @NotNull Sql sql, long segment)
            throws SQLException {
        String lock = sql.dialect() == Dialect.SQLITE ? "" : " FOR UPDATE";
        try (PreparedStatement statement = connection.prepareStatement("SELECT id FROM " + sql.tables().segment()
                + " WHERE id = ?" + lock)) {
            statement.setLong(1, segment);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("SELECT ordinals FROM "
                + sql.tables().segmentFlag() + " WHERE segment = ?")) {
            statement.setLong(1, segment);
            try (ResultSet rows = statement.executeQuery()) {
                return Optional.of(rows.next() ? FlagOrdinals.decode(rows.getBytes(1)) : new int[0]);
            }
        }
    }

    /**
     * Replaces a segment's rolled-back ordinals; an empty set deletes the flag row.
     *
     * @param connection a connection
     * @param sql        the shard's SQL context
     * @param segment    the segment id
     * @param ordinals   the ordinals, strictly ascending
     * @throws SQLException when the statement fails
     */
    public void writeFlags(@NotNull Connection connection, @NotNull Sql sql, long segment, int @NotNull [] ordinals)
            throws SQLException {
        if (ordinals.length == 0) {
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM " + sql.tables().segmentFlag()
                    + " WHERE segment = ?")) {
                statement.setLong(1, segment);
                statement.executeUpdate();
            }
            return;
        }
        String upsert = sql.dialect().upsert(sql.tables().segmentFlag(), List.of("segment", "ordinals"), "segment",
                List.of("ordinals"), 1);
        try (PreparedStatement statement = connection.prepareStatement(upsert)) {
            statement.setLong(1, segment);
            statement.setBytes(2, FlagOrdinals.encode(ordinals));
            statement.executeUpdate();
        }
    }

    /**
     * Deletes segments and their flag rows.
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
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM " + sql.tables().segmentFlag()
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
     * The number of archived events and segments, and the bytes they take.
     *
     * @param connection a connection
     * @param sql        the shard's SQL context
     * @return the totals
     * @throws SQLException when the query fails
     */
    public @NotNull SegmentTotals totals(@NotNull Connection connection, @NotNull Sql sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COALESCE(SUM(events), 0), COUNT(*), "
                + "COALESCE(SUM(LENGTH(header) + LENGTH(body)), 0) FROM " + sql.tables().segment());
                ResultSet rows = statement.executeQuery()) {
            rows.next();
            return new SegmentTotals(rows.getLong(1), rows.getLong(2), rows.getLong(3));
        }
    }

    private static @NotNull BoundSql query(@NotNull Sql sql, @NotNull String where, @NotNull List<Object> parameters) {
        return new BoundSql("SELECT " + CANDIDATE_COLUMNS + " FROM " + sql.tables().segment() + " s LEFT JOIN "
                + sql.tables().segmentFlag() + " f ON f.segment = s.id WHERE " + where, parameters);
    }

    private static @NotNull List<SegmentRow> select(@NotNull Connection connection, @NotNull BoundSql query)
            throws SQLException {
        List<SegmentRow> segments = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(query.sql())) {
            query.bind(statement);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    byte[] flags = rows.getBytes(6);
                    segments.add(new SegmentRow(rows.getLong(1), rows.getLong(2), rows.getLong(3), rows.getInt(4),
                            rows.getBytes(5), flags == null ? new int[0] : FlagOrdinals.decode(flags)));
                }
            }
        }
        return segments;
    }

    /** The ranges sorted by offset, with touching and overlapping ones joined. */
    private static @NotNull List<ByteRange> merge(@NotNull List<ByteRange> ranges) {
        List<ByteRange> sorted = new ArrayList<>(ranges);
        sorted.sort(Comparator.comparingInt(ByteRange::offset));
        List<ByteRange> merged = new ArrayList<>();
        ByteRange current = sorted.getFirst();
        for (ByteRange next : sorted.subList(1, sorted.size())) {
            if (next.offset() <= current.end()) {
                int end = Math.max(current.end(), next.end());
                current = new ByteRange(current.offset(), end - current.offset());
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return merged;
    }

    private static int containing(@NotNull List<ByteRange> fetched, @NotNull ByteRange range) {
        for (int i = 0; i < fetched.size(); i++) {
            ByteRange slice = fetched.get(i);
            if (range.offset() >= slice.offset() && range.end() <= slice.end()) {
                return i;
            }
        }
        throw new IllegalStateException("no fetched slice holds " + range);
    }

    private static void bindLongs(@NotNull PreparedStatement statement, @NotNull List<Long> values)
            throws SQLException {
        for (int i = 0; i < values.size(); i++) {
            statement.setLong(i + 1, values.get(i));
        }
    }
}
