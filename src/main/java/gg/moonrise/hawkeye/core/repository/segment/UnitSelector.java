package gg.moonrise.hawkeye.core.repository.segment;

import gg.moonrise.hawkeye.core.repository.EventFilter;
import gg.moonrise.hawkeye.core.repository.EventFilter.WorldRange;
import gg.moonrise.hawkeye.util.EventIds;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import org.jetbrains.annotations.NotNull;

/**
 * Picks the units of a segment that can hold a row an {@link EventFilter} accepts, from the header
 * alone. It may pick a unit that holds no match, never skip one that holds a match: it checks the id
 * range against slot ranges, the nodes, the worlds, the position ranges against the chunk list and the
 * placed units' key ranges, the actors and the action masks. Unplaced units are not ordered by place,
 * so the area never excludes them.
 */
public final class UnitSelector {

    private final SegmentHeader header;
    private final EventFilter filter;
    private final int firstPlaced;

    /**
     * A selector for one filter.
     *
     * @param header the segment header
     * @param filter the filter
     */
    public UnitSelector(@NotNull SegmentHeader header, @NotNull EventFilter filter) {
        this.header = header;
        this.filter = filter;
        int unplacedUnits = 0;
        while (unplacedUnits < header.unitCount() && !header.unitInfo(unplacedUnits).placed()) {
            unplacedUnits++;
        }
        this.firstPlaced = unplacedUnits;
    }

    /**
     * The units that may match.
     *
     * @return ascending unit numbers
     */
    public int @NotNull [] select() {
        if (!segmentMayMatch()) {
            return new int[0];
        }
        boolean[] actors = wantedActors();
        long actionMask = wantedActions();
        boolean[] area = placedArea().orElse(null);
        int[] out = new int[header.unitCount()];
        int size = 0;
        for (int u = 0; u < header.unitCount(); u++) {
            SegmentUnit unit = header.unitInfo(u);
            if (!slotsMayMatch(header.baseSlot() + unit.minSlot(), header.baseSlot() + unit.maxSlot())
                    || (unit.actionMask() & actionMask) == 0 || !actorsMayMatch(unit.actors(), actors)) {
                continue;
            }
            if (unit.placed() && area != null && !area[u - firstPlaced]) {
                continue;
            }
            out[size++] = u;
        }
        return Arrays.copyOf(out, size);
    }

    private boolean segmentMayMatch() {
        if (filter.minId() > filter.maxId()
                || !slotsMayMatch(header.baseSlot(), header.baseSlot() + header.slotSpan())) {
            return false;
        }
        if (!filter.nodes().isEmpty() && Arrays.stream(header.nodeIds()).noneMatch(filter.nodes()::contains)) {
            return false;
        }
        Set<Integer> areaWorlds = filter.positions().isEmpty() ? filter.worlds()
                : Set.copyOf(filter.positions().stream().map(WorldRange::world).toList());
        return areaWorlds.isEmpty() || Arrays.stream(header.worldIds()).anyMatch(areaWorlds::contains);
    }

    private boolean slotsMayMatch(long minSlot, long maxSlot) {
        long smallest = EventIds.fromSlot(minSlot, 0, 0);
        long largest = EventIds.fromSlot(maxSlot, EventIds.MAX_NODE, EventIds.MAX_RANK);
        return filter.maxId() >= smallest && filter.minId() <= largest;
    }

    /** Per header actor index: whether the filter keeps it. */
    private boolean @NotNull [] wantedActors() {
        int[] actorIds = header.actorIds();
        boolean[] wanted = new boolean[actorIds.length];
        for (int i = 0; i < actorIds.length; i++) {
            Integer actor = actorIds[i];
            wanted[i] = (filter.actors().isEmpty() || filter.actors().contains(actor))
                    && !filter.excludedActors().contains(actor);
        }
        return wanted;
    }

    private static boolean actorsMayMatch(int @NotNull [] unitActors, boolean @NotNull [] wanted) {
        for (int index : unitActors) {
            if (wanted[index]) {
                return true;
            }
        }
        return false;
    }

    private long wantedActions() {
        if (filter.actions().isEmpty()) {
            return -1L;
        }
        long mask = 0;
        for (int action : filter.actions()) {
            mask |= action < 0 ? 0 : SegmentEncoder.actionBit(action);
        }
        return mask;
    }

    /**
     * Per placed unit: whether the filter's area can reach it; empty when the filter has no area or
     * the segment has no placed units.
     */
    private @NotNull Optional<boolean[]> placedArea() {
        int placedUnits = header.unitCount() - firstPlaced;
        if (placedUnits == 0 || filter.positions().isEmpty() && filter.worlds().isEmpty()) {
            return Optional.empty();
        }
        boolean[] area = new boolean[placedUnits];
        if (filter.positions().isEmpty()) {
            markWorlds(area);
        } else {
            for (WorldRange range : filter.positions()) {
                markRange(area, range);
            }
        }
        return Optional.of(area);
    }

    private void markWorlds(boolean @NotNull [] area) {
        int[] worlds = header.worldIds();
        boolean[] wanted = new boolean[worlds.length];
        for (int i = 0; i < worlds.length; i++) {
            wanted[i] = filter.worlds().contains(worlds[i]);
        }
        for (int p = 0; p < area.length; p++) {
            int from = header.unitInfo(firstPlaced + p).world();
            int to = p + 1 < area.length ? header.unitInfo(firstPlaced + p + 1).world() : worlds.length - 1;
            for (int w = from; w <= to && !area[p]; w++) {
                area[p] = wanted[w];
            }
        }
    }

    private void markRange(boolean @NotNull [] area, @NotNull WorldRange range) {
        int world = indexOf(header.worldIds(), range.world());
        if (world < 0 || range.from() > range.to()) {
            return;
        }
        int[] chunkWorlds = header.chunkWorlds();
        long[] chunkBases = header.chunkBases();
        int chunk = firstChunk(chunkWorlds, chunkBases, world, range.from() & ~SegmentHeader.CHUNK_LOCAL_MASK);
        for (; chunk < chunkBases.length && chunkWorlds[chunk] == world && chunkBases[chunk] <= range.to(); chunk++) {
            long low = Math.max(range.from(), chunkBases[chunk]);
            long high = Math.min(range.to(), chunkBases[chunk] | SegmentHeader.CHUNK_LOCAL_MASK);
            markKeys(area, world, low, high);
        }
    }

    /** Marks the placed units whose key range [first key, next unit's first key] meets [low, high]. */
    private void markKeys(boolean @NotNull [] area, int world, long low, long high) {
        int lo = 0;
        int hi = area.length - 1;
        int start = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (compare(firstPlaced + mid, world, low) < 0) {
                start = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        for (int p = start; p < area.length && compare(firstPlaced + p, world, high) <= 0; p++) {
            area[p] = true;
        }
    }

    /** Compares a unit's first key with (world, position). */
    private int compare(int unit, int world, long position) {
        SegmentUnit info = header.unitInfo(unit);
        int byWorld = Integer.compare(info.world(), world);
        return byWorld != 0 ? byWorld : Long.compare(info.position(), position);
    }

    private static int firstChunk(int @NotNull [] worlds, long @NotNull [] bases, int world, long base) {
        int lo = 0;
        int hi = bases.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            int byWorld = Integer.compare(worlds[mid], world);
            if (byWorld < 0 || byWorld == 0 && bases[mid] < base) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private static int indexOf(int @NotNull [] values, int value) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == value) {
                return i;
            }
        }
        return -1;
    }
}
