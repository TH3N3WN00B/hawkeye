package gg.moonrise.hawkeye.core.repository.segment;

import static gg.moonrise.hawkeye.core.repository.segment.SegmentFixtures.WORLD_ORDER;
import static gg.moonrise.hawkeye.core.repository.segment.SegmentFixtures.assertRows;
import static gg.moonrise.hawkeye.core.repository.segment.SegmentFixtures.canonical;
import static gg.moonrise.hawkeye.core.repository.segment.SegmentFixtures.drafts;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.core.repository.EventFilter;
import gg.moonrise.hawkeye.core.repository.EventFilter.Bounds;
import gg.moonrise.hawkeye.core.repository.EventFilter.WorldRange;
import gg.moonrise.hawkeye.core.repository.EventRow;
import gg.moonrise.hawkeye.entity.query.RollbackState;
import gg.moonrise.hawkeye.util.BlockPositions;
import gg.moonrise.hawkeye.util.EventIds;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

@Slf4j
public class SegmentFilterTest {

    private static List<EventRow> rows;
    private static PackedSegment packed;
    private static SegmentHeader header;
    private static List<EventRow> all;

    @BeforeAll
    public static void encodeOnce() {
        rows = canonical(drafts(new Random(11), 40_000, 20_000), WORLD_ORDER);
        packed = Segments.encode(rows, WORLD_ORDER);
        header = SegmentHeader.parse(packed.header());
        all = Segments.decode(header, packed.body(), packed.rolledBack());
    }

    @Test
    public void selectedUnitsHoldEveryMatch() {
        Random random = new Random(5);
        int nonEmpty = 0;
        long selected = 0;
        for (int i = 0; i < 400; i++) {
            EventFilter filter = randomFilter(random);
            List<EventRow> want = all.stream().filter(filter::matches).toList();
            int[] units = header.units(filter);
            selected += units.length;
            List<EventRow> got = decodeUnits(units).stream().filter(filter::matches)
                    .sorted((a, b) -> Long.compare(a.id(), b.id())).toList();
            assertRows(want, got);
            if (!want.isEmpty()) {
                nonEmpty++;
                assertTrue(header.mayMatch(filter), "filter " + filter);
            }
            assertEquals(units.length > 0, header.mayMatch(filter));
            for (int u = 1; u < units.length; u++) {
                assertTrue(units[u] > units[u - 1]);
            }
        }
        log.info("{} of 400 random filters matched; {} units selected on average of {}", nonEmpty,
                selected / 400.0, header.unitCount());
        assertTrue(nonEmpty > 100, "the random filters exercise matches: " + nonEmpty);
    }

    @Test
    public void matchingColumnsFirstFindsExactlyTheMatchingRows() {
        Random random = new Random(17);
        SharedColumns shared = Segments.shared(header, slice(header.shared()));
        Palettes palettes = Segments.palettes(header, slice(header.palettes()));
        assertEquals(shared.palettes(), palettes, "the palette frame alone decodes like the shared frames");
        for (int i = 0; i < 400; i++) {
            EventFilter filter = withTypes(random, randomFilter(random));
            List<EventRow> want = all.stream().filter(filter::matches).toList();
            int[] units = header.units(filter);
            List<byte[]> frames = Arrays.stream(units).mapToObj(unit -> slice(header.unit(unit))).toList();
            SegmentMatch match = Segments.match(header, filter, Optional.of(palettes), units, frames,
                    packed.rolledBack());
            List<EventRow> got = match.rows(shared, filter::matchesId).stream()
                    .sorted((a, b) -> Long.compare(a.id(), b.id())).toList();
            assertRows(want, got);
            assertEquals(want.size(), match.count(shared, filter::matchesId), "count of filter " + filter);
            long columnMatches = all.stream().filter(row -> filter.matchesColumns(row.world(), row.position(),
                    row.actor(), row.action(), row.subject(), row.rolledBack())).count();
            if (filter.minId() == 0 && filter.maxId() == Long.MAX_VALUE && filter.nodes().isEmpty()) {
                // Without id rules the header drops no unit by its slots: every column match is counted.
                assertEquals(columnMatches, match.count(), "column matches of filter " + filter);
            } else {
                assertTrue(match.count() >= want.size() && match.count() <= columnMatches, "filter " + filter);
            }
        }
    }

    @Test
    public void unplacedUnitsAreSkippedByAreasAndWorldsTheirRowsAreNotIn() {
        long chatAt = BlockPositions.pack(100, 64, 100);
        long far = BlockPositions.pack(5_000, 64, 5_000);
        List<EventRow> input = new ArrayList<>();
        long slot = 60_000;
        for (int i = 0; i < 20_000; i++) {
            input.add(new EventRow(EventIds.fromSlot(slot, 1, i), 7, chatAt, 3, 45, 0, 0, 0, 0, new byte[] {1}));
        }
        for (int i = 0; i < 20_000; i++) {
            input.add(new EventRow(EventIds.fromSlot(slot, 1, 20_000 + i), 2, far + (long) (i % 16) * (1L << 12), 4,
                    1, 9, 0, 0, 0, null));
        }
        PackedSegment segment = Segments.encode(input, WORLD_ORDER);
        SegmentHeader parsed = SegmentHeader.parse(segment.header());
        assertEquals(1, parsed.unplacedRegions().size(), "one region holds every chat row");
        int unplacedUnits = 2;

        assertSelectsUnplaced(parsed, block(7, chatAt), true);
        assertSelectsUnplaced(parsed, block(7, BlockPositions.pack(100 + 600, 64, 100)), false);
        assertSelectsUnplaced(parsed, block(2, chatAt), false);
        assertSelectsUnplaced(parsed, worlds(Set.of(7)), true);
        assertSelectsUnplaced(parsed, worlds(Set.of(2)), false);
        assertEquals(unplacedUnits, Arrays.stream(parsed.units(worlds(Set.of(7)))).count(),
                "world 7 holds only the chat units");
        EventFilter wholeChunk = new EventFilter(0, Long.MAX_VALUE, Set.of(), BlockPositions.columnRanges(6, 6, 6, 6)
                .stream().map(range -> WorldRange.of(7, range.from(), range.to())).toList(), Bounds.NONE, Set.of(),
                Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), RollbackState.ANY);
        assertSelectsUnplaced(parsed, wholeChunk, true);
    }

    private static void assertSelectsUnplaced(SegmentHeader parsed, EventFilter filter, boolean expected) {
        boolean selected = Arrays.stream(parsed.units(filter)).anyMatch(unit -> !parsed.unitInfo(unit).placed());
        assertEquals(expected, selected, "unplaced units for " + filter);
    }

    private static EventFilter block(int world, long position) {
        return new EventFilter(0, Long.MAX_VALUE, Set.of(), List.of(WorldRange.of(world, position, position)),
                Bounds.NONE, Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), RollbackState.ANY);
    }

    private static EventFilter worlds(Set<Integer> worlds) {
        return new EventFilter(0, Long.MAX_VALUE, worlds, List.of(), Bounds.NONE, Set.of(), Set.of(), Set.of(),
                Set.of(), Set.of(), Set.of(), RollbackState.ANY);
    }

    private static EventFilter withTypes(Random random, EventFilter filter) {
        Set<Integer> types = new HashSet<>();
        Set<Integer> excluded = new HashSet<>();
        if (random.nextInt(4) == 0) {
            types.add(all.get(random.nextInt(all.size())).subject());
            types.add(random.nextInt(300));
        }
        if (random.nextInt(5) == 0) {
            excluded.add(random.nextInt(300));
        }
        return new EventFilter(filter.minId(), filter.maxId(), filter.worlds(), filter.positions(), filter.bounds(),
                filter.nodes(), filter.actors(), filter.excludedActors(), filter.actions(), types, excluded,
                filter.rollbackState());
    }

    private static byte[] slice(ByteRange range) {
        return Arrays.copyOfRange(packed.body(), range.offset(), range.end());
    }

    @Test
    public void aBlockLookupSkipsMostPlacedUnits() {
        EventRow row = all.stream().filter(r -> r.action() < 40).findFirst().orElseThrow();
        EventFilter filter = new EventFilter(0, Long.MAX_VALUE, Set.of(),
                List.of(WorldRange.of(row.world(), row.position(), row.position())), Bounds.NONE, Set.of(), Set.of(),
                Set.of(), Set.of(row.action()), Set.of(), Set.of(), RollbackState.ANY);
        int[] units = header.units(filter);
        long placedSelected = Arrays.stream(units).filter(u -> header.unitInfo(u).placed()).count();
        assertTrue(placedSelected <= 2, "one block reaches at most two placed units, got " + placedSelected);
        assertTrue(decodeUnits(units).stream().anyMatch(r -> r.id() == row.id()));
    }

    @Test
    public void anIdRangeOutsideTheSegmentMatchesNothing() {
        long after = EventIds.fromSlot(header.baseSlot() + header.slotSpan() + 1, 0, 0);
        assertEquals(0, header.units(EventFilter.everything(after, Long.MAX_VALUE)).length);
        assertTrue(header.mayMatch(EventFilter.everything(0, Long.MAX_VALUE)));
    }

    private static List<EventRow> decodeUnits(int[] units) {
        ByteRange shared = header.shared();
        byte[] sharedBytes = Arrays.copyOfRange(packed.body(), shared.offset(), shared.end());
        List<byte[]> frames = new ArrayList<>();
        for (int unit : units) {
            ByteRange range = header.unit(unit);
            frames.add(Arrays.copyOfRange(packed.body(), range.offset(), range.end()));
        }
        return Segments.decodeUnits(header, sharedBytes, units, frames, packed.rolledBack());
    }

    private static EventFilter randomFilter(Random random) {
        EventRow anchor = all.get(random.nextInt(all.size()));
        long minId = 0;
        long maxId = Long.MAX_VALUE;
        if (random.nextInt(3) == 0) {
            long a = all.get(random.nextInt(all.size())).id();
            long b = all.get(random.nextInt(all.size())).id();
            minId = Math.min(a, b);
            maxId = Math.max(a, b);
        }
        Set<Integer> worlds = new HashSet<>();
        if (random.nextInt(3) == 0) {
            worlds.add(WORLD_ORDER.get(random.nextInt(WORLD_ORDER.size())));
            if (random.nextBoolean()) {
                worlds.add(99);
            }
        }
        List<WorldRange> positions = new ArrayList<>();
        Bounds bounds = Bounds.NONE;
        if (random.nextInt(2) == 0) {
            int chunkX = BlockPositions.chunkX(anchor.position()) + random.nextInt(3) - 1;
            int chunkZ = BlockPositions.chunkZ(anchor.position()) + random.nextInt(3) - 1;
            int size = random.nextInt(3);
            int minLocalX = random.nextInt(4) == 0 ? random.nextInt(16) : 0;
            for (BlockPositions.Range range : BlockPositions.columnRanges(chunkX, chunkZ, chunkX + size, chunkZ + size)) {
                positions.add(new WorldRange(random.nextInt(5) == 0 ? 2 : anchor.world(), range.from(), range.to(),
                        minLocalX, 15));
            }
            if (random.nextInt(4) == 0) {
                int y = BlockPositions.y(anchor.position());
                bounds = new Bounds(y - 5, y + 5, Integer.MIN_VALUE, Integer.MAX_VALUE);
            }
            if (random.nextInt(4) == 0) {
                positions = List.of(WorldRange.of(anchor.world(), anchor.position(), anchor.position()));
                bounds = Bounds.NONE;
            }
        }
        Set<Integer> nodes = random.nextInt(4) == 0 ? Set.of(EventIds.node(anchor.id())) : Set.of();
        Set<Integer> actors = new HashSet<>();
        if (random.nextInt(3) == 0) {
            actors.add(anchor.actor());
            actors.add(1 + random.nextInt(30));
        }
        Set<Integer> excluded = random.nextInt(5) == 0 ? Set.of(1 + random.nextInt(30)) : Set.of();
        Set<Integer> actions = new HashSet<>();
        if (random.nextInt(3) == 0) {
            actions.add(anchor.action());
            actions.add(random.nextInt(140));
        }
        RollbackState state = RollbackState.values()[random.nextInt(RollbackState.values().length)];
        return new EventFilter(minId, maxId, worlds, positions, bounds, nodes, actors, excluded, actions, Set.of(),
                Set.of(), state);
    }
}
