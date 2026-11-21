package gg.moonrise.hawkeye.util.codec.binary;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

public class CompressionTest {

    private static byte[] repetitive(int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) (i % 7);
        }
        return bytes;
    }

    private static byte[] random(int length, long seed) {
        byte[] bytes = new byte[length];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }

    private static byte[] roundTrip(byte[] frame) {
        return Compression.decompress(frame, 0, frame.length);
    }

    @Test
    public void zstdLoadsOnTheBuildHost() {
        assertTrue(Compression.zstdAvailable());
    }

    @Test
    public void preferredCodecIsZstd() {
        byte[] raw = repetitive(10_000);
        byte[] frame = Compression.compress(raw);
        assertEquals(Compression.ZSTD, frame[0]);
        assertTrue(frame.length < raw.length / 10);
        assertArrayEquals(raw, roundTrip(frame));
    }

    @Test
    public void deflateRoundTrips() {
        byte[] raw = repetitive(10_000);
        byte[] frame = Compression.compress(raw, Compression.DEFLATE);
        assertEquals(Compression.DEFLATE, frame[0]);
        assertArrayEquals(raw, roundTrip(frame));
    }

    @Test
    public void incompressibleBytesAreStored() {
        byte[] raw = random(1000, 1);
        for (int codec : new int[] {Compression.ZSTD, Compression.DEFLATE, Compression.STORED}) {
            byte[] frame = Compression.compress(raw, codec);
            assertEquals(Compression.STORED, frame[0]);
            assertArrayEquals(raw, roundTrip(frame));
        }
    }

    @Test
    public void emptyInputRoundTrips() {
        byte[] frame = Compression.compress(new byte[0]);
        assertArrayEquals(new byte[0], roundTrip(frame));
    }

    @Test
    public void readsAFrameInsideALargerArray() {
        byte[] raw = repetitive(5000);
        byte[] frame = Compression.compress(raw);
        byte[] padded = new byte[frame.length + 20];
        System.arraycopy(frame, 0, padded, 7, frame.length);
        assertArrayEquals(raw, Compression.decompress(padded, 7, frame.length));
    }

    @Test
    public void compressionIsDeterministic() {
        byte[] raw = repetitive(50_000);
        assertArrayEquals(Compression.compress(raw), Compression.compress(raw));
        assertArrayEquals(Compression.compress(raw, Compression.DEFLATE), Compression.compress(raw, Compression.DEFLATE));
    }

    @Test
    public void rejectsAnUnknownCodec() {
        byte[] frame = Compression.compress(repetitive(100));
        frame[0] = 9;
        assertThrows(CodecException.class, () -> roundTrip(frame));
        assertThrows(IllegalArgumentException.class, () -> Compression.compress(new byte[1], 9));
    }

    @Test
    public void rejectsAnOversizedRawLength() {
        ByteWriter out = new ByteWriter(16);
        out.writeByte(Compression.STORED).writeVarLong(Compression.MAX_RAW_BYTES + 1L);
        byte[] frame = out.toByteArray();
        assertThrows(CodecException.class, () -> roundTrip(frame));
    }

    @Test
    public void rejectsAWrongRawLength() {
        for (int codec : new int[] {Compression.ZSTD, Compression.DEFLATE}) {
            byte[] frame = Compression.compress(repetitive(1000), codec);
            byte[] longer = frame.clone();
            // the raw length 1000 is a two-byte varint at offset 1
            longer[1] = (byte) ((1001 & 0x7F) | 0x80);
            assertThrows(CodecException.class, () -> roundTrip(longer));
            byte[] shorter = frame.clone();
            shorter[1] = (byte) ((999 & 0x7F) | 0x80);
            assertThrows(CodecException.class, () -> roundTrip(shorter));
        }
        byte[] stored = Compression.compress(random(100, 2));
        byte[] wrong = stored.clone();
        wrong[1] = 99;
        assertThrows(CodecException.class, () -> roundTrip(wrong));
    }

    @Test
    public void rejectsTruncatedAndCorruptFrames() {
        Random random = new Random(3);
        for (int codec : new int[] {Compression.ZSTD, Compression.DEFLATE}) {
            byte[] raw = repetitive(20_000);
            byte[] frame = Compression.compress(raw, codec);
            for (int cut = 0; cut < frame.length; cut += Math.max(1, frame.length / 40)) {
                byte[] truncated = Arrays.copyOf(frame, cut);
                assertThrows(CodecException.class, () -> roundTrip(truncated), "cut at " + cut);
            }
            for (int i = 0; i < 200; i++) {
                byte[] flipped = frame.clone();
                int at = random.nextInt(frame.length);
                flipped[at] ^= (byte) (1 + random.nextInt(255));
                try {
                    byte[] decoded = roundTrip(flipped);
                    assertEquals(raw.length, decoded.length);
                } catch (CodecException expected) {
                    // corruption detected
                }
            }
        }
    }

    @Test
    public void rejectsASliceOutsideTheArray() {
        byte[] frame = Compression.compress(repetitive(100));
        assertThrows(CodecException.class, () -> Compression.decompress(frame, 1, frame.length));
    }
}
