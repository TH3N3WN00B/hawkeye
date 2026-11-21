package gg.moonrise.hawkeye.core.storage.segment;

import static gg.moonrise.hawkeye.core.storage.segment.SegmentFixtures.WORLD_ORDER;
import static gg.moonrise.hawkeye.core.storage.segment.SegmentFixtures.canonical;
import static gg.moonrise.hawkeye.core.storage.segment.SegmentFixtures.drafts;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import gg.moonrise.hawkeye.core.repository.event.EventRow;
import gg.moonrise.hawkeye.util.codec.binary.CodecException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

@Slf4j
public class SegmentCorruptionTest {

    private static PackedSegment packed;
    private static PackedSegment withRanks;

    @BeforeAll
    public static void encodeOnce() {
        Random random = new Random(3);
        List<EventRow> rows = canonical(drafts(random, 18_000, 3_000), WORLD_ORDER);
        packed = Segments.encode(rows, WORLD_ORDER);
        List<EventRow> shifted = new ArrayList<>(rows.size());
        for (EventRow row : rows) {
            shifted.add(new EventRow(row.id() + 3, row.world(), row.position(), row.actor(), row.action(),
                    row.subject(), row.secondary(), row.amount(), row.flags(), row.data()));
        }
        withRanks = Segments.encode(shifted, WORLD_ORDER);
    }

    @Test
    public void truncatedHeadersAreRejected() {
        byte[] header = packed.header();
        for (int cut = 0; cut < header.length; cut++) {
            byte[] truncated = Arrays.copyOf(header, cut);
            assertThrows(CodecException.class, () -> SegmentHeader.parse(truncated), "cut at " + cut);
        }
    }

    @Test
    public void flippedHeaderBytesFailCleanly() {
        for (PackedSegment segment : List.of(packed, withRanks)) {
            Random random = new Random(9);
            int decoded = 0;
            for (int i = 0; i < 600; i++) {
                byte[] header = segment.header().clone();
                header[random.nextInt(header.length)] ^= (byte) (1 + random.nextInt(255));
                decoded += attempt(() -> {
                    SegmentHeader parsed = SegmentHeader.parse(header);
                    Segments.decode(parsed, segment.body(), segment.rolledBack());
                    decodeEachUnit(parsed, segment.body(), segment.rolledBack());
                });
            }
            log.info("{} of 600 flipped headers still decoded", decoded);
        }
    }

    @Test
    public void truncatedBodiesAreRejected() {
        SegmentHeader header = SegmentHeader.parse(packed.header());
        Random random = new Random(4);
        for (int i = 0; i < 100; i++) {
            byte[] truncated = Arrays.copyOf(packed.body(), random.nextInt(packed.body().length));
            assertThrows(CodecException.class, () -> Segments.decode(header, truncated, packed.rolledBack()));
        }
        ByteRange range = header.unit(0);
        byte[] shared = Arrays.copyOfRange(packed.body(), 0, header.shared().length());
        byte[] unit = Arrays.copyOfRange(packed.body(), range.offset(), range.end() - 1);
        assertThrows(CodecException.class,
                () -> Segments.decodeUnits(header, shared, new int[] {0}, List.of(unit), new int[0]));
        assertThrows(CodecException.class, () -> Segments.ids(header, Arrays.copyOf(shared, shared.length - 1)));
    }

    @Test
    public void flippedBodyBytesFailCleanly() {
        for (PackedSegment segment : List.of(packed, withRanks)) {
            SegmentHeader header = SegmentHeader.parse(segment.header());
            assertTrue(segment == packed || header.frameLength(SharedFrame.RANK) > 0);
            Random random = new Random(10);
            int decoded = 0;
            for (int i = 0; i < 600; i++) {
                byte[] body = segment.body().clone();
                // half the flips land in the shared frames, where every decode starts
                int at = i % 2 == 0 ? random.nextInt(header.shared().length()) : random.nextInt(body.length);
                body[at] ^= (byte) (1 + random.nextInt(255));
                decoded += attempt(() -> Segments.decode(header, body, segment.rolledBack()));
            }
            log.info("{} of 600 flipped bodies still decoded", decoded);
        }
    }

    private static void decodeEachUnit(SegmentHeader header, byte[] body, int[] rolledBack) {
        ByteRange shared = header.shared();
        byte[] sharedBytes = Arrays.copyOfRange(body, shared.offset(), Math.min(body.length, shared.end()));
        for (int u = 0; u < header.unitCount(); u++) {
            ByteRange range = header.unit(u);
            byte[] frames = Arrays.copyOfRange(body, Math.min(range.offset(), body.length),
                    Math.min(range.end(), body.length));
            Segments.decodeUnits(header, sharedBytes, new int[] {u}, List.of(frames), rolledBack);
        }
    }

    /** Runs a decode: 1 when it succeeds, 0 on a {@link CodecException}, a failure on anything else. */
    private static int attempt(Runnable decode) {
        try {
            decode.run();
            return 1;
        } catch (CodecException expected) {
            return 0;
        } catch (IllegalArgumentException e) {
            // only the caller-supplied ordinals may be refused this way
            if (e.getMessage() != null && e.getMessage().startsWith("rolled-back ordinals")) {
                return 0;
            }
            fail("unexpected " + e, e);
            return 0;
        } catch (RuntimeException | OutOfMemoryError e) {
            fail("unexpected " + e, e);
            return 0;
        }
    }
}
