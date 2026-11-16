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
     * read from archive segments. {@code EventFilterTest} checks that both agree. It is
     * {@link #matchesId} and {@link #matchesColumns}; an archive read applies the two separately, the
     * column rules first.
     *
     * @param row the row
     * @return {@code true} when the row matches
     */
    public boolean matches(@NotNull EventRow row) {
        return matchesId(row.id()) && matchesColumns(row.world(), row.position(), row.actor(), row.action(),
                row.subject(), row.rolledBack());
    }

    /**
     * The rules that need a row's id: the id range and the nodes.
     *
     * @param id the event id
     * @return {@code true} when the id passes
     */
    public boolean matchesId(long id) {
        return id >= minId && id <= maxId && (nodes.isEmpty() || nodes.contains(EventIds.node(id)));
    }

    /**
     * Every rule that a row's own columns decide (all but {@link #matchesId}).
     *
     * @param world      the world id
     * @param position   the packed position
     * @param actor      the actor id
     * @param action     the action code
     * @param subject    the subject type id, or 0
     * @param rolledBack whether the row is rolled back
     * @return {@code true} when the row passes
     */
    public boolean matchesColumns(int world, long position, int actor, int action, int subject, boolean rolledBack) {
        return matchesPlace(world, position) && matchesActor(actor) && matchesAction(action)
                && matchesType(subject) && matchesRollback(rolledBack);
    }

    /**
     * Whether a filter's place rules need the position, not just the world ({@link #matchesWorld}).
     *
     * @return {@code true} when there are position ranges
     */
    public boolean hasArea() {
        return !positions.isEmpty();
    }

    /**
     * The place rule: inside a position range when there are any ({@link #inPositions}), else in a
     * listed world.
     *
     * @param world    the world id
     * @param position the packed position
     * @return {@code true} when the place passes
     */
    public boolean matchesPlace(int world, long position) {
        return positions.isEmpty() ? matchesWorld(world) : inPositions(world, position);
    }

    /**
     * The whole-world rule, used when there are no position ranges.
     *
     * @param world the world id
     * @return {@code true} when no world is listed or the world is
     */
    public boolean matchesWorld(int world) {
        return worlds.isEmpty() || worlds.contains(world);
    }

    /**
     * Whether a place is inside one of the position ranges (with its local-x trim) and the bounds.
     *
     * @param world    the world id
     * @param position the packed position
     * @return {@code true} when inside
     */
    public boolean inPositions(int world, long position) {
        int localX = (int) ((position >>> 16) & 15);
        boolean inside = false;
        for (WorldRange range : positions) {
            if (range.world() == world && position >= range.from() && position <= range.to()
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
     * The actor rule: listed (when any are) and not excluded.
     *
     * @param actor the actor id
     * @return {@code true} when the actor passes
     */
    public boolean matchesActor(int actor) {
        return (actors.isEmpty() || actors.contains(actor)) && !excludedActors.contains(actor);
    }

    /**
     * Whether the actor rule can drop a row.
     *
     * @return {@code true} when actors are listed or excluded
     */
    public boolean filtersActors() {
        return !actors.isEmpty() || !excludedActors.isEmpty();
    }

    /**
     * The action rule.
     *
     * @param action the action code
     * @return {@code true} when no action is listed or the action is
     */
    public boolean matchesAction(int action) {
        return actions.isEmpty() || actions.contains(action);
    }

    /**
     * The subject type rule: listed (when any are) and not excluded.
     *
     * @param subject the subject type id, or 0
     * @return {@code true} when the subject passes
     */
    public boolean matchesType(int subject) {
        return (types.isEmpty() || types.contains(subject)) && !excludedTypes.contains(subject);
    }

    /**
     * Whether the type rule can drop a row.
     *
     * @return {@code true} when types are listed or excluded
     */
    public boolean filtersTypes() {
        return !types.isEmpty() || !excludedTypes.isEmpty();
    }

    /**
     * The rolled-back rule.
     *
     * @param rolledBack whether the row is rolled back
     * @return {@code true} when the flag passes
     */
    public boolean matchesRollback(boolean rolledBack) {
        return switch (rollbackState) {
            case ANY -> true;
            case ACTIVE -> !rolledBack;
            case ROLLED_BACK -> rolledBack;
        };
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
