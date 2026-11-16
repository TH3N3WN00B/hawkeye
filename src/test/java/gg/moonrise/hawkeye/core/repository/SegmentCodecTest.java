package gg.moonrise.hawkeye.core.repository;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.util.BlockPositions;
import gg.moonrise.hawkeye.util.EventIds;
import gg.moonrise.hawkeye.util.codec.CodecException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

public class SegmentCodecTest {

    private static final long T = EventIds.EPOCH_MILLIS + 86_400_000L * 300;

    @Test
    public void roundTripsEveryFieldInNegativeAndPositiveRegions() {
        for (int[] origin : new int[][] {{0, 0}, {-128, -128}, {-300, 900}, {29_999_872, -29_999_872}}) {
            List<EventRow> rows = rows(origin[0], origin[1], 500, 2);
            long region = BlockPositions.regionKey(rows.getFirst().position());
            List<EventRow> decoded = SegmentCodec.decode(SegmentCodec.encode(rows, region), 7, region);
            assertEquals(rows.size(), decoded.size());
            for (int i = 0; i < rows.size(); i++) {
                EventRow expected = rows.get(i);
                EventRow actual = decoded.get(i);
                assertEquals(expected.id(), actual.id());
                assertEquals(expected.position(), actual.position());
                assertEquals(expected.actor(), actual.actor());
                assertEquals(expected.action(), actual.action());
                assertEquals(expected.subject(), actual.subject());
                assertEquals(expected.secondary(), actual.secondary());
                assertEquals(expected.amount(), actual.amount());
                assertEquals(expected.flags(), actual.flags());
                assertArrayEquals(expected.data(), actual.data());
                assertEquals(7, actual.world());
            }
        }
    }

    @Test
    public void packsEvenRandomEventsIntoAFewBytesEach() {
        List<EventRow> rows = rows(0, 0, 2_000, 1);
        byte[] body = SegmentCodec.encode(rows, BlockPositions.regionKey(rows.getFirst().position()));
        double perEvent = body.length / (double) rows.size();
        // Uniformly random fields are the worst case; real builds compress far better (StorageFootprintTest).
        assertTrue(perEvent < 12, perEvent + " bytes per event");
    }

    @Test
    public void refusesEventsOutsideTheRegionUnsortedInputAndCorruptBodies() {
        List<EventRow> rows = rows(0, 0, 3, 1);
        assertThrows(IllegalArgumentException.class, () -> SegmentCodec.encode(rows, BlockPositions.regionKey(5, 5)));
        List<EventRow> reversed = new ArrayList<>(rows);
        Collections.reverse(reversed);
        long region = BlockPositions.regionKey(rows.getFirst().position());
        assertThrows(IllegalArgumentException.class, () -> SegmentCodec.encode(reversed, region));
        assertThrows(CodecException.class, () -> SegmentCodec.decode(new byte[] {1, 2, 3}, 1, region));
        assertThrows(CodecException.class, () -> SegmentCodec.decode(new byte[] {9}, 1, region));
    }

    private static List<EventRow> rows(int originX, int originZ, int count, int nodes) {
        Random random = new Random(originX * 31L + originZ);
        List<EventRow> rows = new ArrayList<>();
        long millis = T;
        for (int i = 0; i < count; i++) {
            millis += 1 + random.nextInt(3000);
            int x = (originX & ~127) + random.nextInt(128);
            int z = (originZ & ~127) + random.nextInt(128);
            byte[] data = random.nextInt(20) == 0 ? new byte[] {1, (byte) i, 3} : null;
            rows.add(new EventRow(EventIds.compose(millis, 1 + random.nextInt(nodes), i % 4096),
                    1, BlockPositions.pack(x, random.nextInt(320) - 64, z), 1 + random.nextInt(12),
                    random.nextBoolean() ? 1 : 2, 1 + random.nextInt(30), random.nextInt(4), random.nextInt(3) * 16,
                    random.nextInt(8) == 0 ? EventRow.ROLLED_BACK : 0, data));
        }
        return rows;
    }
}
