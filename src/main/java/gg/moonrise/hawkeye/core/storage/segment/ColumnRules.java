package gg.moonrise.hawkeye.core.storage.segment;

import gg.moonrise.hawkeye.core.repository.event.EventFilter;
import gg.moonrise.hawkeye.entity.query.RollbackState;
import gg.moonrise.hawkeye.util.codec.CodecException;
import java.util.Arrays;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;

/**
 * The rules of an {@link EventFilter} that a unit's own columns decide (world, area, actors, actions,
 * types, rolled-back state), prepared for one segment: per header world index and per palette index
 * the answer of {@link EventFilter}'s rule for that value, so a row costs an array lookup. Only the
 * columns a rule needs are decoded.
 */
public final class ColumnRules {

    private final SegmentHeader header;
    private final EventFilter filter;
    private final int[] worlds;
    private final boolean[] worldWanted;
    private final boolean[] actorWanted;
    private final boolean[] typeWanted;
    private final boolean actions;
    private final int lastWorld;
    private final long lastPosition;

    /**
     * Prepares the rules.
     *
     * @param header   the segment header
     * @param filter   the filter
     * @param palettes the segment's palettes; required when {@link #needsPalettes} says so
     * @throws IllegalArgumentException when the palettes are needed and missing
     */
    public ColumnRules(@NotNull SegmentHeader header, @NotNull EventFilter filter,
            @NotNull Optional<Palettes> palettes) {
        if (needsPalettes(filter) && palettes.isEmpty()) {
            throw new IllegalArgumentException("the filter names actors or types: the palettes are needed");
        }
        this.header = header;
        this.filter = filter;
        this.worlds = header.worldIds();
        boolean[] wanted = new boolean[worlds.length];
        boolean all = true;
        for (int i = 0; i < worlds.length; i++) {
            wanted[i] = filter.matchesWorld(worlds[i]);
            all &= wanted[i];
        }
        this.worldWanted = filter.hasArea() || all ? null : wanted;
        this.actorWanted = filter.filtersActors() ? actorRule(filter, palettes.orElseThrow().actors()) : null;
        this.typeWanted = filter.filtersTypes() ? typeRule(filter, palettes.orElseThrow().types()) : null;
        this.actions = !filter.actions().isEmpty();
        int maxWorld = -1;
        long maxPosition = Long.MIN_VALUE;
        for (EventFilter.WorldRange range : filter.positions()) {
            int index = indexOf(worlds, range.world());
            if (index > maxWorld || index == maxWorld && range.to() > maxPosition) {
                maxWorld = index;
                maxPosition = range.to();
            }
        }
        this.lastWorld = maxWorld;
        this.lastPosition = maxPosition;
    }

    /**
     * Whether matching a filter's columns needs the palette frame.
     *
     * @param filter the filter
     * @return {@code true} when it names actors or types
     */
    public static boolean needsPalettes(@NotNull EventFilter filter) {
        return filter.filtersActors() || filter.filtersTypes();
    }

    /**
     * The rows of a unit whose columns pass every column rule.
     *
     * @param scan       the unit
     * @param rolledBack the segment's ascending rolled-back ordinals
     * @return ascending row numbers within the unit
     * @throws CodecException when a column is corrupt
     */
    public int @NotNull [] match(@NotNull UnitScan scan, int @NotNull [] rolledBack) {
        int rows = scan.rows();
        if (filter.hasArea() && header.unitInfo(scan.unit()).placed()) {
            // Placed rows are sorted by (world index, position): none after the area's last key can match.
            rows = lastWorld < 0 ? 0 : scan.positionsUpTo(lastWorld, lastPosition);
        }
        int[] worldIndexes = worldWanted != null || filter.hasArea() ? scan.worldIndexes() : null;
        long[] positions = filter.hasArea() ? positionsOf(scan) : null;
        int[] actorIndexes = actorWanted != null ? scan.actorIndexes() : null;
        int[] actionCodes = actions ? scan.actions() : null;
        int[] subjects = typeWanted != null ? scan.subjects() : null;
        boolean flags = filter.rollbackState() != RollbackState.ANY;
        int start = scan.start();
        int flagAt = UnitDecoder.firstAtOrAfter(rolledBack, start);
        int[] out = new int[rows];
        int size = 0;
        for (int i = 0; i < rows; i++) {
            if (worldWanted != null && !worldWanted[worldIndexes[i]]
                    || positions != null && !filter.inPositions(worlds[worldIndexes[i]], positions[i])
                    || actorIndexes != null && !wanted(actorWanted, actorIndexes[i], "actor")
                    || actionCodes != null && !filter.matchesAction(actionCodes[i])
                    || subjects != null && !wanted(typeWanted, subjects[i], "type")) {
                continue;
            }
            if (flags) {
                int ordinal = start + i;
                while (flagAt < rolledBack.length && rolledBack[flagAt] < ordinal) {
                    flagAt++;
                }
                if (!filter.matchesRollback(flagAt < rolledBack.length && rolledBack[flagAt] == ordinal)) {
                    continue;
                }
            }
            out[size++] = i;
        }
        return size == out.length ? out : Arrays.copyOf(out, size);
    }

    /** The positions array; for a placed unit only the rows up to the area's last key are filled. */
    private long @NotNull [] positionsOf(@NotNull UnitScan scan) {
        return header.unitInfo(scan.unit()).placed() ? scan.decodedPositions() : scan.positions();
    }

    private static int indexOf(int @NotNull [] values, int value) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == value) {
                return i;
            }
        }
        return -1;
    }

    private static boolean wanted(boolean @NotNull [] rule, int index, @NotNull String what) {
        if (index >= rule.length) {
            throw new CodecException(what + " index " + index + " out of " + rule.length);
        }
        return rule[index];
    }

    private static boolean @NotNull [] actorRule(@NotNull EventFilter filter, int @NotNull [] palette) {
        boolean[] wanted = new boolean[palette.length];
        for (int i = 0; i < palette.length; i++) {
            wanted[i] = filter.matchesActor(palette[i]);
        }
        return wanted;
    }

    /** Indexed by the subject column: 0 is no type, {@code i + 1} is palette entry {@code i}. */
    private static boolean @NotNull [] typeRule(@NotNull EventFilter filter, int @NotNull [] palette) {
        boolean[] wanted = new boolean[palette.length + 1];
        wanted[0] = filter.matchesType(0);
        for (int i = 0; i < palette.length; i++) {
            wanted[i + 1] = filter.matchesType(palette[i]);
        }
        return wanted;
    }
}
