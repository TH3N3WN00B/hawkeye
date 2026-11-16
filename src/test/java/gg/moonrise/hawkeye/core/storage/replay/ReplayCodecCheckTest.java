package gg.moonrise.hawkeye.core.storage.replay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import gg.moonrise.hawkeye.core.queue.IdAssigner;
import gg.moonrise.hawkeye.core.repository.EventRow;
import gg.moonrise.hawkeye.core.repository.segment.PackedSegment;
import gg.moonrise.hawkeye.core.repository.segment.SegmentHeader;
import gg.moonrise.hawkeye.core.repository.segment.Segments;
import gg.moonrise.hawkeye.core.repository.segment.SharedFrame;
import gg.moonrise.hawkeye.entity.event.PendingEvent;
import gg.moonrise.hawkeye.util.EventIds;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;

/**
 * Opt-in: packs a slice of an exported CoreProtect history with the segment codec alone (no
 * database) and reports bytes per event. {@code HAWKEYE_REPLAY_DUMP} is the export folder,
 * {@code HAWKEYE_REPLAY_DAY} the day and {@code HAWKEYE_REPLAY_EVENTS} the most events.
 */
public class ReplayCodecCheckTest {

    private static final int SEGMENT_EVENTS = 262_144;

    @Test
    public void aSliceOfRealHistoryPacksWithoutRanks(TestReporter reporter) throws IOException {
        String folder = System.getenv("HAWKEYE_REPLAY_DUMP");
        assumeTrue(folder != null && !folder.isBlank(), "set HAWKEYE_REPLAY_DUMP to run");
        ReferenceDump dump = new ReferenceDump(Path.of(folder));
        int day = Integer.parseInt(System.getenv().getOrDefault("HAWKEYE_REPLAY_DAY",
                String.valueOf(dump.days().get(1))));
        long limit = Long.parseLong(System.getenv().getOrDefault("HAWKEYE_REPLAY_EVENTS", "2000000"));
        ReferenceEvents events = new ReferenceEvents(dump);
        IdAssigner assigner = new IdAssigner();
        Pack pack = new Pack();
        try {
            events.replay(day, (second, captured) -> {
                if (pack.events >= limit) {
                    throw new Done();
                }
                assigner.add(captured);
                pack.add(assigner.take((second + 1) * 1000 + IdAssigner.GRACE_MILLIS, false));
            });
        } catch (Done done) {
            // the slice is full
        }
        pack.add(assigner.take(Long.MAX_VALUE, true));
        pack.flush();
        reporter.publishEntry("result", String.format("%d events, %d segments, %.3f B/event (header %.3f, body %.3f), "
                        + "rank frames %d bytes in %d segments, time frames %.3f B/event, encode %.1f s",
                pack.events, pack.segments, (double) (pack.headerBytes + pack.bodyBytes) / pack.events,
                (double) pack.headerBytes / pack.events, (double) pack.bodyBytes / pack.events, pack.rankBytes,
                pack.rankSegments, (double) pack.timeBytes / pack.events, pack.encodeNanos / 1e9));
    }

    /** Signals the end of the slice from inside the replay callback. */
    private static final class Done extends RuntimeException {

        private static final long serialVersionUID = 1L;
    }

    /** Resolves names to ids in memory and packs every {@link #SEGMENT_EVENTS} events. */
    private static final class Pack {

        private final Map<String, Integer> worlds = new TreeMap<>();
        private final Map<String, Integer> actors = new HashMap<>();
        private final Map<String, Integer> types = new HashMap<>();
        private final List<EventRow> rows = new ArrayList<>();
        private long events;
        private long segments;
        private long headerBytes;
        private long bodyBytes;
        private long rankBytes;
        private long rankSegments;
        private long timeBytes;
        private long encodeNanos;

        private void add(List<PendingEvent> batch) {
            for (PendingEvent event : batch) {
                rows.add(new EventRow(EventIds.fromStamp(event.stamp(), 1),
                        worlds.computeIfAbsent(event.world(), name -> worlds.size() + 1),
                        event.position(),
                        actors.computeIfAbsent(event.actor().key(), key -> actors.size() + 1),
                        event.action().code(),
                        event.subject().isEmpty() ? 0 : types.computeIfAbsent(event.subject(), key -> types.size() + 1),
                        event.secondary() == null ? 0 : types.computeIfAbsent(event.secondary(),
                                key -> types.size() + 1),
                        Math.max(0, event.amount()), 0, event.data()));
                events++;
                if (rows.size() == SEGMENT_EVENTS) {
                    flush();
                }
            }
        }

        private void flush() {
            if (rows.isEmpty()) {
                return;
            }
            // The writer ranks by world name, so the packer orders worlds by name.
            List<Integer> worldOrder = new ArrayList<>(worlds.values());
            long start = System.nanoTime();
            PackedSegment segment = Segments.encode(rows, worldOrder);
            encodeNanos += System.nanoTime() - start;
            SegmentHeader header = SegmentHeader.parse(segment.header());
            if (segments == 0) {
                List<EventRow> sorted = new ArrayList<>(rows);
                sorted.sort(Comparator.comparingLong(EventRow::id));
                List<EventRow> decoded = Segments.decode(header, segment.body(), new int[0]);
                assertEquals(sorted.size(), decoded.size());
                for (int row = 0; row < sorted.size(); row += 997) {
                    assertEquals(sorted.get(row).id(), decoded.get(row).id());
                    assertEquals(sorted.get(row).position(), decoded.get(row).position());
                    assertEquals(sorted.get(row).subject(), decoded.get(row).subject());
                }
            }
            segments++;
            headerBytes += segment.header().length;
            bodyBytes += segment.body().length;
            int rank = header.frameLength(SharedFrame.RANK);
            rankBytes += rank;
            rankSegments += rank > 0 ? 1 : 0;
            timeBytes += header.frameLength(SharedFrame.TIME);
            rows.clear();
        }
    }
}
