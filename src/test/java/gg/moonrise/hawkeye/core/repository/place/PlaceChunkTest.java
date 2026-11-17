package gg.moonrise.hawkeye.core.repository.place;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.util.BlockPositions;
import gg.moonrise.hawkeye.util.codec.ByteWriter;
import gg.moonrise.hawkeye.util.codec.CodecException;
import gg.moonrise.hawkeye.util.codec.Compression;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

public class PlaceChunkTest {

    private static final long CHUNK = PlaceKeys.chunk(BlockPositions.pack(100, 64, 100));
    private static final int BUSY = PlaceKeys.local(BlockPositions.pack(100, 64, 100));
    private static final int LONELY = PlaceKeys.local(BlockPositions.pack(111, -60, 103));
    private static final int OTHER = PlaceKeys.local(BlockPositions.pack(101, 64, 100));

    @Test
    public void roundTripsPositionsWithExactIdsOnlyAndWithBothParts() {
        PlaceChunk chunk = new PlaceChunk(1, CHUNK);
        for (int segment = 1; segment < 200; segment++) {
            chunk.add(BUSY, segment);
        }
        chunk.add(LONELY, 3);
        chunk.add(LONELY, 150);
        chunk.add(OTHER, 7);
        PlaceChunk decoded = PlaceChunk.fromBody(1, CHUNK, chunk.toBody(9));
        assertEquals(chunk, decoded);
        assertEquals(IdRuns.of(3, 150), decoded.positions().get(LONELY).exact());
        assertTrue(decoded.positions().get(LONELY).older().isEmpty());
        assertEquals(IdRuns.range(200 - PlaceChunk.EXACT_SEGMENTS, 199), decoded.positions().get(BUSY).exact());
        assertEquals(IdRuns.range(0, PlaceChunk.bucket(199 - PlaceChunk.EXACT_SEGMENTS)),
                decoded.positions().get(BUSY).older());
    }

    @Test
    public void theIdAfterTheExactLimitDemotesTheSmallest() {
        PlaceChunk chunk = new PlaceChunk(1, CHUNK, 3);
        chunk.add(BUSY, 40);
        chunk.add(BUSY, 50);
        chunk.add(BUSY, 60);
        assertEquals(IdRuns.of(40, 50, 60), chunk.positions().get(BUSY).exact());
        assertTrue(chunk.positions().get(BUSY).older().isEmpty());

        chunk.add(BUSY, 70);
        assertEquals(IdRuns.of(50, 60, 70), chunk.positions().get(BUSY).exact());
        assertEquals(IdRuns.of(PlaceChunk.bucket(40)), chunk.positions().get(BUSY).older());

        chunk.add(BUSY, 5);
        assertEquals(IdRuns.of(50, 60, 70), chunk.positions().get(BUSY).exact(), "older than every exact id");
        assertEquals(IdRuns.of(PlaceChunk.bucket(5), PlaceChunk.bucket(40)), chunk.positions().get(BUSY).older());
        chunk.add(BUSY, 60);
        assertEquals(IdRuns.of(50, 60, 70), chunk.positions().get(BUSY).exact(), "an id added again changes nothing");
        assertEquals(IdRuns.of(PlaceChunk.bucket(5), PlaceChunk.bucket(40)), chunk.positions().get(BUSY).older());
        assertEquals(chunk, PlaceChunk.fromBody(1, CHUNK, chunk.toBody(9), 3));
        assertThrows(CodecException.class, () -> PlaceChunk.fromBody(1, CHUNK, chunk.toBody(9), 2),
                "more exact ids than the limit");
    }

    @Test
    public void theOrderOfAdditionsDoesNotChangeTheRow() {
        List<long[]> touches = new ArrayList<>();
        Random random = new Random(29);
        for (int segment = 1; segment <= 300; segment++) {
            for (int local : new int[] {BUSY, LONELY, OTHER}) {
                if (local == BUSY || random.nextInt(local == OTHER ? 3 : 40) == 0) {
                    touches.add(new long[] {local, segment});
                }
            }
        }
        PlaceChunk ascending = build(touches, list -> { });
        PlaceChunk descending = build(touches, Collections::reverse);
        PlaceChunk shuffled = build(touches, list -> Collections.shuffle(list, new Random(31)));
        assertEquals(ascending, descending);
        assertEquals(ascending, shuffled);
        assertArrayEquals(ascending.toBody(9), descending.toBody(9));
        assertArrayEquals(ascending.toBody(9), shuffled.toBody(9));
        assertTrue(!ascending.positions().get(OTHER).older().isEmpty(), "the test reaches the buckets");
    }

    @Test
    public void trimDropsExactIdsAndBucketsBelowTheOldestSegment() {
        PlaceChunk chunk = new PlaceChunk(1, CHUNK, 4);
        for (int segment = 1; segment <= 100; segment++) {
            chunk.add(BUSY, segment);
        }
        chunk.add(LONELY, 3);
        chunk.add(LONELY, 98);
        chunk.trim(40);
        assertEquals(IdRuns.range(97, 100), chunk.positions().get(BUSY).exact());
        assertEquals(IdRuns.range(PlaceChunk.bucket(40), PlaceChunk.bucket(96)), chunk.positions().get(BUSY).older());
        assertEquals(IdRuns.of(98), chunk.positions().get(LONELY).exact());

        chunk.trim(99);
        assertEquals(IdRuns.range(99, 100), chunk.positions().get(BUSY).exact());
        assertEquals(IdRuns.of(PlaceChunk.bucket(99)), chunk.positions().get(BUSY).older(),
                "the oldest segment's bucket stays");
        assertTrue(!chunk.positions().containsKey(LONELY), "a position with nothing left goes");
        chunk.trim(101);
        assertTrue(!chunk.isEmpty(), "101 is in the bucket of 96..111");
        chunk.trim(112);
        assertTrue(chunk.isEmpty());
    }

    @Test
    public void removeDropsAnExactIdEverywhere() {
        PlaceChunk chunk = new PlaceChunk(1, CHUNK, 2);
        chunk.add(BUSY, 10);
        chunk.add(BUSY, 20);
        chunk.add(BUSY, 30);
        chunk.add(LONELY, 30);
        chunk.remove(30);
        assertEquals(IdRuns.of(20), chunk.positions().get(BUSY).exact());
        assertEquals(IdRuns.of(PlaceChunk.bucket(10)), chunk.positions().get(BUSY).older());
        assertTrue(!chunk.positions().containsKey(LONELY));
        chunk.remove(20);
        assertTrue(chunk.positions().get(BUSY).exact().isEmpty(), "buckets keep the position");
        PlaceChunk decoded = PlaceChunk.fromBody(1, CHUNK, chunk.toBody(9), 2);
        assertEquals(chunk, decoded, "a position with older buckets only round-trips");
    }

    @Test
    public void theLookupReaderReturnsBothSetsAndStopsAtTheLargestKey() {
        PlaceChunk chunk = new PlaceChunk(1, CHUNK, 3);
        for (int segment = 1; segment <= 40; segment++) {
            chunk.add(BUSY, segment);
        }
        chunk.add(OTHER, 5);
        chunk.add(LONELY, 9);
        chunk.add(LONELY, 33);
        byte[] body = chunk.toBody(9);

        PlaceChunk.Touches busy = PlaceChunk.segmentsWhere(body, local -> local == BUSY, BUSY);
        assertEquals(IdRuns.range(38, 40), busy.exact());
        assertEquals(IdRuns.range(0, PlaceChunk.bucket(37)), busy.older());

        PlaceChunk.Touches both = PlaceChunk.segmentsWhere(body, local -> local == OTHER || local == LONELY,
                Math.max(OTHER, LONELY));
        assertEquals(IdRuns.of(5, 9, 33), both.exact());
        assertTrue(both.older().isEmpty());

        PlaceChunk.Touches stopped = PlaceChunk.segmentsWhere(body, local -> true, Math.min(LONELY, Math.min(BUSY, OTHER)) - 1);
        assertTrue(stopped.isEmpty(), "no key at or below the bound");
        PlaceChunk.Touches none = PlaceChunk.segmentsWhere(body, local -> local == LONELY, LONELY - 1);
        assertTrue(none.isEmpty(), "the read stops at the largest key asked for");
    }

    @Test
    public void corruptBodiesAreRejected() {
        assertThrows(CodecException.class, () -> PlaceChunk.fromBody(1, CHUNK, body(out -> out.writeVarLong(5))),
                "more positions than bytes");
        assertThrows(CodecException.class, () -> PlaceChunk.fromBody(1, CHUNK, body(out -> {
            out.writeVarLong(2);
            position(out, 7, 3);
            out.writeVarLong(0);
            out.writeVarLong(1 << 1);
            out.writeZigZag(1);
            out.writeVarLong(0);
        })), "positions out of order");
        assertThrows(CodecException.class, () -> PlaceChunk.fromBody(1, CHUNK, body(out -> {
            out.writeVarLong(1);
            out.writeVarLong(PlaceKeys.LOCAL_MASK + 1);
            out.writeVarLong(1 << 1);
            out.writeZigZag(1);
            out.writeVarLong(0);
        })), "key out of range");
        assertThrows(CodecException.class, () -> PlaceChunk.fromBody(1, CHUNK, body(out -> {
            out.writeVarLong(1);
            out.writeVarLong(7);
            out.writeVarLong(0);
        })), "a position with no segment");
        assertThrows(CodecException.class, () -> PlaceChunk.fromBody(1, CHUNK, body(out -> {
            out.writeVarLong(1);
            out.writeVarLong(7);
            out.writeVarLong(1);
            out.writeVarLong(0);
        })), "older buckets flagged but none listed");
        assertThrows(CodecException.class, () -> PlaceChunk.fromBody(1, CHUNK, body(out -> {
            out.writeVarLong(1);
            position(out, 7, 3);
            out.writeVarLong(9);
        })), "unread bytes");
        assertThrows(CodecException.class, () -> PlaceChunk.fromBody(1, CHUNK, body(out -> {
            out.writeVarLong(1);
            out.writeVarLong(7);
            out.writeVarLong(1 << 1);
            out.writeZigZag(-4);
            out.writeVarLong(0);
        })), "a negative segment id");
    }

    private static void position(ByteWriter out, int keyDelta, long segment) {
        out.writeVarLong(keyDelta);
        out.writeVarLong(1 << 1);
        out.writeZigZag(segment);
        out.writeVarLong(0);
    }

    private static byte[] body(Consumer<ByteWriter> writer) {
        ByteWriter out = new ByteWriter(16);
        writer.accept(out);
        return Compression.compressAt(out.toByteArray(), 3);
    }

    private static PlaceChunk build(List<long[]> touches, Consumer<List<long[]>> order) {
        List<long[]> copy = new ArrayList<>(touches);
        order.accept(copy);
        PlaceChunk chunk = new PlaceChunk(1, CHUNK, 8);
        copy.forEach(touch -> chunk.add((int) touch[0], touch[1]));
        return chunk;
    }
}
