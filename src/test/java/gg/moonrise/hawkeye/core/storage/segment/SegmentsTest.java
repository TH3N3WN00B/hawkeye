package gg.moonrise.hawkeye.core.storage.segment;

import static gg.moonrise.hawkeye.core.storage.segment.SegmentFixtures.WORLD_ORDER;
import static gg.moonrise.hawkeye.core.storage.segment.SegmentFixtures.assertRows;
import static gg.moonrise.hawkeye.core.storage.segment.SegmentFixtures.canonical;
import static gg.moonrise.hawkeye.core.storage.segment.SegmentFixtures.drafts;
import static gg.moonrise.hawkeye.core.storage.segment.SegmentFixtures.expected;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.core.repository.event.EventRow;
import gg.moonrise.hawkeye.util.BlockPositions;
import gg.moonrise.hawkeye.util.EventIds;
import gg.moonrise.hawkeye.util.codec.Compression;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;

@Slf4j
public class SegmentsTest {

    private static List<EventRow> rows;
    private static PackedSegment packed;
    private static SegmentHeader header;

    @BeforeAll
    public static void encodeOnce() {
        rows = canonical(drafts(new Random(42), 20_000, 18_000), WORLD_ORDER);
        long start = System.nanoTime();
        packed = Segments.encode(rows, WORLD_ORDER);
        log.info("encoded {} events in {} ms: {} header + {} body bytes", rows.size(),
                (System.nanoTime() - start) / 1_000_000, packed.header().length, packed.body().length);
        header = SegmentHeader.parse(packed.header());
    }

    @Test
    public void roundTripsRandomEvents() {
        assertRows(expected(rows), Segments.decode(header, packed.body(), packed.rolledBack()));
        assertEquals(rows.size(), packed.events());
        assertEquals(rows.stream().mapToLong(EventRow::id).min().orElseThrow(), packed.firstId());
        assertEquals(rows.stream().mapToLong(EventRow::id).max().orElseThrow(), packed.lastId());
        assertTrue(header.unitCount() >= 4, "both groups span several units");
        assertEquals(List.of(7, 2, 11, 5), header.worlds());
    }

    @Test
    public void canonicalIdsStoreNoRanks() {
        assertEquals(0, header.frameLength(SharedFrame.RANK));
        assertTrue(header.frameLength(SharedFrame.NODE) > 0, "several nodes");
    }

    @Test
    public void aPerturbedIdStillRoundTrips() {
        List<EventRow> perturbed = new ArrayList<>(rows);
        EventRow row = perturbed.get(1234);
        long id = row.id();
        long moved = EventIds.fromSlot(EventIds.slot(id), EventIds.node(id), EventIds.MAX_RANK - 7);
        perturbed.set(1234, new EventRow(moved, row.world(), row.position(), row.actor(), row.action(), row.subject(),
                row.secondary(), row.amount(), row.flags(), row.data()));
        PackedSegment segment = Segments.encode(perturbed, WORLD_ORDER);
        SegmentHeader parsed = SegmentHeader.parse(segment.header());
        assertTrue(parsed.frameLength(SharedFrame.RANK) > 0);
        assertRows(expected(perturbed), Segments.decode(parsed, segment.body(), segment.rolledBack()));
    }

    @Test
    public void ranksAreExactForAnyIds() {
        Random random = new Random(7);
        List<EventRow> odd = new ArrayList<>();
        long slot = 90_000;
        for (int i = 0; i < 3000; i++) {
            long id = switch (i % 4) {
                // off the one-second grid: any millisecond and sequence
                case 0 -> EventIds.compose(EventIds.EPOCH_MILLIS + slot * 1000 + random.nextInt(5000), 1 + i % 3,
                        random.nextInt(EventIds.MAX_SEQUENCE + 1));
                // a second whose first ranks are missing
                case 1 -> EventIds.fromSlot(slot + 10, 4, 100 + i);
                // two rows of one slot with a gap between their ranks
                case 2 -> EventIds.fromSlot(slot + 20 + i % 7, 5, i * 13 % 997 + (i % 2) * 2000);
                default -> EventIds.fromSlot(slot + random.nextInt(40), 6 + i % 2, i);
            };
            odd.add(new EventRow(id, 2, BlockPositions.pack(random.nextInt(64), 70, -random.nextInt(64)), 3,
                    random.nextInt(2) == 0 ? 1 : 45, 4, 0, 1, 0, null));
        }
        List<EventRow> unique = odd.stream().filter(distinctIds()).toList();
        PackedSegment segment = Segments.encode(unique, List.of(2));
        SegmentHeader parsed = SegmentHeader.parse(segment.header());
        assertRows(expected(unique), Segments.decode(parsed, segment.body(), segment.rolledBack()));
    }

    private static Predicate<EventRow> distinctIds() {
        Set<Long> seen = new HashSet<>();
        return row -> seen.add(row.id());
    }

    @Test
    public void idsFollowStoredOrderAndUnitsCoverTheSegment() {
        ByteRange shared = header.shared();
        byte[] sharedBytes = Arrays.copyOfRange(packed.body(), shared.offset(), shared.end());
        long[] ids = Segments.ids(header, sharedBytes);
        int[] all = new int[header.unitCount()];
        List<byte[]> frames = new ArrayList<>();
        for (int u = 0; u < all.length; u++) {
            all[u] = u;
            ByteRange range = header.unit(u);
            frames.add(Arrays.copyOfRange(packed.body(), range.offset(), range.end()));
        }
        List<EventRow> stored = Segments.decodeUnits(header, sharedBytes, all, frames, packed.rolledBack());
        assertArrayEquals(ids, stored.stream().mapToLong(EventRow::id).toArray());
        List<EventRow> sorted = new ArrayList<>(stored);
        sorted.sort(Comparator.comparingLong(EventRow::id));
        assertRows(Segments.decode(header, packed.body(), packed.rolledBack()), sorted);
        for (int ordinal : packed.rolledBack()) {
            assertTrue(stored.get(ordinal).rolledBack());
        }
        assertEquals(packed.rolledBack().length, stored.stream().filter(EventRow::rolledBack).count());
        assertTrue(stored.stream().allMatch(row -> row.flags() == 0 || row.flags() == EventRow.ROLLED_BACK));
    }

    @Test
    public void decodesTheGivenRolledBackOrdinals() {
        int[] flagged = {0, 5, header.events() - 1};
        List<EventRow> decoded = Segments.decode(header, packed.body(), flagged);
        ByteRange shared = header.shared();
        long[] ids = Segments.ids(header, Arrays.copyOfRange(packed.body(), shared.offset(), shared.end()));
        Set<Long> expectedIds = Set.of(ids[0], ids[5], ids[header.events() - 1]);
        for (EventRow row : decoded) {
            assertEquals(expectedIds.contains(row.id()), row.rolledBack());
        }
        assertThrows(IllegalArgumentException.class, () -> Segments.decode(header, packed.body(), new int[] {3, 2}));
    }

    @Test
    public void encodingIsDeterministicAndIgnoresInputOrder(TestReporter reporter) {
        List<EventRow> shuffled = new ArrayList<>(rows);
        Collections.shuffle(shuffled, new Random(1));
        long single = System.nanoTime();
        assertEquals(packed, Segments.encode(shuffled, WORLD_ORDER));
        reporter.publishEntry("encodeSingle", rows.size() + " events on one thread in "
                + (System.nanoTime() - single) / 1_000_000 + " ms");
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            long start = System.nanoTime();
            assertEquals(packed, Segments.encode(shuffled, WORLD_ORDER, executor));
            reporter.publishEntry("encode", rows.size() + " events on 4 threads in "
                    + (System.nanoTime() - start) / 1_000_000 + " ms");
        } finally {
            executor.shutdown();
        }
    }

    @Test
    public void deflateRoundTrips() {
        PackedSegment deflated = Segments.encode(rows, WORLD_ORDER, Runnable::run, Compression.DEFLATE);
        SegmentHeader parsed = SegmentHeader.parse(deflated.header());
        ByteRange time = new ByteRange(0, parsed.frameLength(SharedFrame.TIME));
        assertEquals(Compression.DEFLATE, deflated.body()[time.offset()]);
        assertRows(expected(rows), Segments.decode(parsed, deflated.body(), deflated.rolledBack()));
    }

    @Test
    public void rejectsBadInput() {
        assertThrows(IllegalArgumentException.class, () -> Segments.encode(List.of(), WORLD_ORDER));
        assertThrows(IllegalArgumentException.class, () -> Segments.encode(rows, List.of(7, 2, 11)));
        List<EventRow> duplicated = new ArrayList<>(rows);
        EventRow first = rows.getFirst();
        duplicated.add(new EventRow(first.id(), first.world(), 0, 1, 1, 0, 0, 0, 0, null));
        assertThrows(IllegalArgumentException.class, () -> Segments.encode(duplicated, WORLD_ORDER));
    }

    @Test
    public void decodesOneUnitQuickly(TestReporter reporter) {
        ByteRange shared = header.shared();
        SharedColumns columns = Segments.shared(header,
                Arrays.copyOfRange(packed.body(), shared.offset(), shared.end()));
        int unit = header.unitCount() - 1;
        ByteRange range = header.unit(unit - 1);
        byte[] frames = Arrays.copyOfRange(packed.body(), range.offset(), range.end());
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 30; i++) {
            long start = System.nanoTime();
            List<EventRow> decoded = Segments.decodeUnits(header, columns, new int[] {unit - 1}, List.of(frames),
                    packed.rolledBack());
            best = Math.min(best, System.nanoTime() - start);
            assertEquals(Segments.UNIT_ROWS, decoded.size());
        }
        reporter.publishEntry("decodeUnit", Segments.UNIT_ROWS + " rows in " + best / 1000 + " us (best of 30)");
        assertTrue(best < 50_000_000L, "a unit decodes in well under 50 ms, took " + best / 1000 + " us");
    }
}
