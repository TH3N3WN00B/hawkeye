package gg.moonrise.hawkeye.core.repository;

import gg.moonrise.hawkeye.entity.query.RollbackState;
import gg.moonrise.hawkeye.util.BlockPositions;
import gg.moonrise.hawkeye.util.EventIds;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.NotNull;

/**
 * A query with every name resolved to dictionary ids, ready to become SQL. Empty sets mean "no
 * filter". When {@code positions} is not empty it replaces {@code worlds} as the area condition, and
 * {@code bounds} trims the chunk-aligned ranges to the exact block box.
 *
 * @param minId          the smallest id (the time window's start)
 * @param maxId          the largest id (the time window's end)
 * @param worlds         world ids to read (area: whole worlds)
 * @param positions      packed-position ranges per world (area: a box or a block)
 * @param bounds         exact y and z bounds of a box, or {@link Bounds#NONE}
 * @param nodes          node ids of the servers to read
 * @param actors         actor ids to keep
 * @param excludedActors actor ids to drop
 * @param actions        action codes to keep
 * @param types          subject type ids to keep
 * @param excludedTypes  subject type ids to drop
 * @param rollbackState  the rolled-back flag filter
 */
public record EventFilter(
        long minId,
        long maxId,
        @NotNull Set<Integer> worlds,
        @NotNull List<WorldRange> positions,
        @NotNull Bounds bounds,
        @NotNull Set<Integer> nodes,
        @NotNull Set<Integer> actors,
        @NotNull Set<Integer> excludedActors,
        @NotNull Set<Integer> actions,
        @NotNull Set<Integer> types,
        @NotNull Set<Integer> excludedTypes,
        @NotNull RollbackState rollbackState) {

    /**
     * Copies the collections.
     *
     * @param minId          the smallest id
     * @param maxId          the largest id
     * @param worlds         world ids
     * @param positions      position ranges
     * @param bounds         exact box bounds
     * @param nodes          node ids
     * @param actors         actor ids
     * @param excludedActors excluded actor ids
     * @param actions        action codes
     * @param types          type ids
     * @param excludedTypes  excluded type ids
     * @param rollbackState  the flag filter
     */
    public EventFilter {
        worlds = Set.copyOf(worlds);
        positions = List.copyOf(positions);
        nodes = Set.copyOf(nodes);
        actors = Set.copyOf(actors);
        excludedActors = Set.copyOf(excludedActors);
        actions = Set.copyOf(actions);
        types = Set.copyOf(types);
        excludedTypes = Set.copyOf(excludedTypes);
    }

    /**
     * A filter that matches every event in an id range.
     *
     * @param minId the smallest id
     * @param maxId the largest id
     * @return the filter
     */
    public static @NotNull EventFilter everything(long minId, long maxId) {
        return new EventFilter(minId, maxId, Set.of(), List.of(), Bounds.NONE, Set.of(), Set.of(), Set.of(), Set.of(),
                Set.of(), Set.of(), RollbackState.ANY);
    }

    /**
     * Whether a row passes this filter: the same predicate {@link EventSql} writes as SQL, for events
     * read from archive segments. {@code EventFilterTest} checks that both agree.
     *
     * @param row the row
     * @return {@code true} when the row matches
     */
    public boolean matches(@NotNull EventRow row) {
        if (row.id() < minId || row.id() > maxId) {
            return false;
        }
        if (!positions.isEmpty()) {
            if (!inPositions(row)) {
                return false;
            }
        } else if (!worlds.isEmpty() && !worlds.contains(row.world())) {
            return false;
        }
        if (!nodes.isEmpty() && !nodes.contains(EventIds.node(row.id()))) {
            return false;
        }
        if (!actors.isEmpty() && !actors.contains(row.actor()) || excludedActors.contains(row.actor())) {
            return false;
        }
        if (!actions.isEmpty() && !actions.contains(row.action())) {
            return false;
        }
        if (!types.isEmpty() && !types.contains(row.subject()) || excludedTypes.contains(row.subject())) {
            return false;
        }
        return switch (rollbackState) {
            case ANY -> true;
            case ACTIVE -> !row.rolledBack();
            case ROLLED_BACK -> row.rolledBack();
        };
    }

    private boolean inPositions(@NotNull EventRow row) {
        long position = row.position();
        int localX = (int) ((position >>> 16) & 15);
        boolean inside = false;
        for (WorldRange range : positions) {
            if (range.world() == row.world() && position >= range.from() && position <= range.to()
                    && localX >= range.minLocalX() && localX <= range.maxLocalX()) {
                inside = true;
                break;
            }
        }
        if (!inside || !bounds.active()) {
            return inside;
        }
        int y = BlockPositions.y(position);
        int z = BlockPositions.z(position);
        return y >= bounds.minY() && y <= bounds.maxY() && z >= bounds.minZ() && z <= bounds.maxZ();
    }

    /**
     * An inclusive range of packed positions in one world: one chunk column of a box, or one block.
     *
     * @param world     the world id
     * @param from      the first packed position
     * @param to        the last packed position
     * @param minLocalX the smallest {@code x & 15} inside the box in this column (0 when unbounded)
     * @param maxLocalX the largest {@code x & 15} inside the box in this column (15 when unbounded)
     */
    public record WorldRange(int world, long from, long to, int minLocalX, int maxLocalX) {

        /**
         * A range with no local-x trimming.
         *
         * @param world the world id
         * @param from  the first packed position
         * @param to    the last packed position
         * @return the range
         */
        public static @NotNull WorldRange of(int world, long from, long to) {
            return new WorldRange(world, from, to, 0, 15);
        }

        /**
         * Whether the local-x bounds trim anything.
         *
         * @return {@code false} when every local x is inside
         */
        public boolean trimsX() {
            return minLocalX > 0 || maxLocalX < 15;
        }
    }

    /**
     * Exact y and z bounds of a box (x is trimmed per column by {@link WorldRange}).
     *
     * @param minY smallest y
     * @param maxY largest y
     * @param minZ smallest z
     * @param maxZ largest z
     */
    public record Bounds(int minY, int maxY, int minZ, int maxZ) {

        /** No trimming: the ranges are exact already (a single block, or no area). */
        public static final Bounds NONE = new Bounds(Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE,
                Integer.MAX_VALUE);

        /**
         * Whether these bounds trim anything.
         *
         * @return {@code false} for {@link #NONE}
         */
        public boolean active() {
            return !equals(NONE);
        }
    }
}
