package gg.moonrise.hawkeye.core.repository;

import gg.moonrise.hawkeye.core.sql.Dialect;
import gg.moonrise.hawkeye.core.sql.Tables;
import gg.moonrise.hawkeye.util.BlockPositions;
import gg.moonrise.hawkeye.util.EventIds;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.StringJoiner;
import org.jetbrains.annotations.NotNull;

/**
 * Builds the {@code event} queries from an {@link EventFilter}. One builder serves every engine; the
 * plans it aims for are in docs/architecture.md ("Indexes and query shapes"):
 * <ul>
 *   <li>an area becomes one {@code (world = ? AND pos BETWEEN ? AND ?)} term per chunk column, OR-ed,
 *       which every engine answers with index range scans on {@code (world, pos)}; residual
 *       predicates trim the chunk-aligned ranges to the exact box. They only extract low bits
 *       ({@code (pos >> n) & mask}), which gives the same answer whether an engine shifts
 *       arithmetically (SQLite, PostgreSQL) or logically (MySQL);</li>
 *   <li>a whole-world filter is written as {@code (world + 0) IN (...)} so no engine uses the
 *       position index for it and the newest-first primary-key scan stays the plan;</li>
 *   <li>a server filter reads the node bits of the id.</li>
 * </ul>
 */
public final class EventSql {

    /** The columns of every select and insert, in order. */
    public static final List<String> COLUMNS = List.of("id", "world", "pos", "actor", "action", "subject",
            "secondary", "amount", "flags", "data");

    private static final String SELECT = String.join(", ", COLUMNS);
    private static final long Z_OFFSET = 1L << 25;

    private EventSql() {
    }

    /**
     * A page of events.
     *
     * @param tables      the table names
     * @param filter      the filter
     * @param cursor      exclusive keyset bound: rows with ids beyond it in the reading direction are
     *                    skipped ({@code 0} for the first page newest-first or oldest-first)
     * @param limit       the most rows
     * @param newestFirst the order
     * @return the statement
     */
    public static @NotNull BoundSql select(@NotNull Tables tables, @NotNull EventFilter filter, long cursor, int limit,
            boolean newestFirst) {
        List<Object> parameters = new ArrayList<>();
        String where = where(filter, cursor, newestFirst, parameters);
        parameters.add(limit);
        return new BoundSql("SELECT " + SELECT + " FROM " + tables.event() + " WHERE " + where + " ORDER BY id "
                + (newestFirst ? "DESC" : "ASC") + " LIMIT ?", parameters);
    }

    /**
     * The number of matching events.
     *
     * @param tables the table names
     * @param filter the filter
     * @return the statement
     */
    public static @NotNull BoundSql count(@NotNull Tables tables, @NotNull EventFilter filter) {
        List<Object> parameters = new ArrayList<>();
        String where = where(filter, 0, true, parameters);
        return new BoundSql("SELECT COUNT(*) FROM " + tables.event() + " WHERE " + where, parameters);
    }

    /**
     * Deletes up to {@code limit} of the oldest events below an id, optionally only in some worlds.
     *
     * @param dialect the engine
     * @param tables  the table names
     * @param belowId the exclusive upper id bound
     * @param worlds  world ids to purge, or empty for all
     * @param limit   the most rows
     * @return the statement
     */
    public static @NotNull BoundSql deleteBatch(@NotNull Dialect dialect, @NotNull Tables tables, long belowId,
            @NotNull Collection<Integer> worlds, int limit) {
        List<Object> parameters = new ArrayList<>();
        parameters.add(belowId);
        String condition = "id < ?" + (worlds.isEmpty() ? "" : " AND world IN (" + list(worlds, parameters) + ")");
        parameters.add(limit);
        String sql = switch (dialect) {
            case MYSQL -> "DELETE FROM " + tables.event() + " WHERE " + condition + " ORDER BY id LIMIT ?";
            case SQLITE, POSTGRESQL -> "DELETE FROM " + tables.event() + " WHERE id IN (SELECT id FROM "
                    + tables.event() + " WHERE " + condition + " ORDER BY id LIMIT ?)";
        };
        return new BoundSql(sql, parameters);
    }

    /**
     * Sets or clears the rolled-back flag of some events.
     *
     * @param tables     the table names
     * @param ids        the event ids
     * @param rolledBack the new flag value
     * @return the statement
     */
    public static @NotNull BoundSql setRolledBack(@NotNull Tables tables, @NotNull Collection<Long> ids,
            boolean rolledBack) {
        List<Object> parameters = new ArrayList<>();
        String flag = rolledBack ? "flags - (flags & 1) + 1" : "flags - (flags & 1)";
        return new BoundSql("UPDATE " + tables.event() + " SET flags = " + flag + " WHERE id IN ("
                + list(ids, parameters) + ")", parameters);
    }

    private static @NotNull String where(@NotNull EventFilter filter, long cursor, boolean newestFirst,
            @NotNull List<Object> parameters) {
        long min = filter.minId();
        long max = filter.maxId();
        if (cursor != 0) {
            if (newestFirst) {
                max = Math.min(max, cursor - 1);
            } else {
                min = Math.max(min, cursor + 1);
            }
        }
        List<String> conditions = new ArrayList<>();
        conditions.add("id BETWEEN ? AND ?");
        parameters.add(min);
        parameters.add(max);
        if (!filter.positions().isEmpty()) {
            StringJoiner area = new StringJoiner(" OR ", "(", ")");
            for (EventFilter.WorldRange range : filter.positions()) {
                parameters.add(range.world());
                parameters.add(range.from());
                if (range.from() == range.to()) {
                    area.add("(world = ? AND pos = ?)");
                    continue;
                }
                parameters.add(range.to());
                if (range.trimsX()) {
                    area.add("(world = ? AND pos BETWEEN ? AND ? AND ((pos >> 16) & 15) BETWEEN ? AND ?)");
                    parameters.add(range.minLocalX());
                    parameters.add(range.maxLocalX());
                } else {
                    area.add("(world = ? AND pos BETWEEN ? AND ?)");
                }
            }
            conditions.add(area.toString());
            EventFilter.Bounds bounds = filter.bounds();
            if (bounds.active()) {
                conditions.add("(pos & 4095) BETWEEN ? AND ?");
                parameters.add(bounds.minY() - BlockPositions.MIN_Y);
                parameters.add(bounds.maxY() - BlockPositions.MIN_Y);
                // (chunk z + 2^21) * 16 + (z & 15) = z + 2^25: non-negative and monotonic in z.
                conditions.add("(((pos >> 20) & 4194303) * 16 + ((pos >> 12) & 15)) BETWEEN ? AND ?");
                parameters.add((long) bounds.minZ() + Z_OFFSET);
                parameters.add((long) bounds.maxZ() + Z_OFFSET);
            }
        } else if (!filter.worlds().isEmpty()) {
            conditions.add("(world + 0) IN (" + list(filter.worlds(), parameters) + ")");
        }
        if (!filter.nodes().isEmpty()) {
            conditions.add("((id >> " + EventIds.SEQUENCE_BITS + ") & " + EventIds.MAX_NODE + ") IN ("
                    + list(filter.nodes(), parameters) + ")");
        }
        in(conditions, "actor", false, filter.actors(), parameters);
        in(conditions, "actor", true, filter.excludedActors(), parameters);
        in(conditions, "action", false, filter.actions(), parameters);
        in(conditions, "subject", false, filter.types(), parameters);
        in(conditions, "subject", true, filter.excludedTypes(), parameters);
        switch (filter.rollbackState()) {
            case ANY -> {
                // No flag condition.
            }
            case ACTIVE -> conditions.add("(flags & 1) = 0");
            case ROLLED_BACK -> conditions.add("(flags & 1) = 1");
        }
        return String.join(" AND ", conditions);
    }

    private static void in(@NotNull List<String> conditions, @NotNull String column, boolean negated,
            @NotNull Collection<Integer> values, @NotNull List<Object> parameters) {
        if (!values.isEmpty()) {
            conditions.add(column + (negated ? " NOT IN (" : " IN (") + list(values, parameters) + ")");
        }
    }

    private static @NotNull String list(@NotNull Collection<? extends Number> values,
            @NotNull List<Object> parameters) {
        parameters.addAll(values);
        return Dialect.placeholders(values.size());
    }
}
