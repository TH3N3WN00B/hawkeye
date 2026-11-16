package gg.moonrise.hawkeye.core.repository.segment;

import static gg.moonrise.hawkeye.core.repository.segment.SegmentFixtures.assertRows;
import static gg.moonrise.hawkeye.core.repository.segment.SegmentFixtures.canonical;
import static gg.moonrise.hawkeye.core.repository.segment.SegmentFixtures.expected;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.core.repository.EventRow;
import gg.moonrise.hawkeye.core.repository.segment.SegmentFixtures.Draft;
import gg.moonrise.hawkeye.util.BlockPositions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;

@Slf4j
public class SegmentSizeTest {

    private static final int[] AMOUNTS = {1, 16, 32, 64};

    @Test
    public void repetitiveEventsPackUnderTwoBytesEach(TestReporter reporter) {
        Random random = new Random(21);
        List<Draft> drafts = new ArrayList<>(200_000);
        long capture = 0;
        for (int p = 0; p < 500; p++) {
            long position = BlockPositions.pack(random.nextInt(2000) - 1000, random.nextInt(100), random.nextInt(2000)
                    - 1000);
            int[] subjects = {1 + random.nextInt(100), 101 + random.nextInt(100), 201 + random.nextInt(100)};
            int actor = 1 + random.nextInt(20);
            long slot = 10_000 + random.nextInt(100);
            for (int i = 0; i < 400; i++) {
                slot += random.nextInt(3);
                drafts.add(new Draft(slot, 1, capture++, 3, position, actor, 21, subjects[random.nextInt(3)], 0,
                        AMOUNTS[random.nextInt(4)], 0, null));
            }
        }
        List<EventRow> rows = canonical(drafts, List.of(3));
        long start = System.nanoTime();
        PackedSegment packed = Segments.encode(rows, List.of(3));
        long encodeMillis = (System.nanoTime() - start) / 1_000_000;
        SegmentHeader header = SegmentHeader.parse(packed.header());
        assertEquals(0, header.frameLength(SharedFrame.RANK));
        start = System.nanoTime();
        List<EventRow> decoded = Segments.decode(header, packed.body(), packed.rolledBack());
        long decodeMillis = (System.nanoTime() - start) / 1_000_000;
        assertRows(expected(rows), decoded);
        double perEvent = (packed.header().length + packed.body().length) / (double) rows.size();
        String report = String.format("%.3f B/event (%d header + %d body bytes), encode %d ms, decode %d ms",
                perEvent, packed.header().length, packed.body().length, encodeMillis, decodeMillis);
        log.info("repetitive data: {}", report);
        reporter.publishEntry("repetitive", report);
        assertTrue(perEvent < 2.0, "packed size " + perEvent + " B/event");
        SharedColumns shared = Segments.shared(header, Arrays.copyOfRange(packed.body(), 0, header.shared().length()));
        ByteRange range = header.unit(0);
        byte[] frames = Arrays.copyOfRange(packed.body(), range.offset(), range.end());
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 30; i++) {
            long unitStart = System.nanoTime();
            Segments.decodeUnits(header, shared, new int[] {0}, List.of(frames), packed.rolledBack());
            best = Math.min(best, System.nanoTime() - unitStart);
        }
        reporter.publishEntry("repetitiveUnit", Segments.UNIT_ROWS + " rows in " + best / 1000 + " us (best of 30)");
    }

    @Test
    public void encodesAQuarterMillionEventsInSeconds(TestReporter reporter) {
        List<Draft> drafts = SegmentFixtures.drafts(new Random(8), 230_000, 32_144).stream()
                .map(d -> new Draft(d.slot(), d.node(), d.capture(), d.world(), d.position(), d.actor(), d.action(),
                        d.subject(), d.secondary(), d.amount(), d.flags(),
                        d.data() != null && d.data().length > 64 ? null : d.data()))
                .toList();
        List<EventRow> rows = canonical(drafts, SegmentFixtures.WORLD_ORDER);
        long start = System.nanoTime();
        PackedSegment packed = Segments.encode(rows, SegmentFixtures.WORLD_ORDER);
        long encodeMillis = (System.nanoTime() - start) / 1_000_000;
        SegmentHeader header = SegmentHeader.parse(packed.header());
        start = System.nanoTime();
        List<EventRow> decoded = Segments.decode(header, packed.body(), packed.rolledBack());
        long decodeMillis = (System.nanoTime() - start) / 1_000_000;
        assertEquals(rows.size(), decoded.size());
        String report = String.format("%d random events: %.2f B/event, encode %d ms, decode %d ms", rows.size(),
                (packed.header().length + packed.body().length) / (double) rows.size(), encodeMillis, decodeMillis);
        log.info(report);
        reporter.publishEntry("quarterMillion", report);
        assertTrue(encodeMillis < 60_000, report);
    }
}
