package gg.moonrise.hawkeye.util.nbt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.util.codec.binary.CodecException;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

public class NbtGzipTest {

    private static final byte[] RAW = new NbtRoot("", NbtReaderTest.compound("id",
            new NbtTag.NbtString("minecraft:diamond_sword"))).write();

    @Test
    public void detectsBothForms() {
        byte[] gzip = NbtGzip.gzip(RAW);
        assertTrue(NbtGzip.isGzip(gzip));
        assertFalse(NbtGzip.isRaw(gzip));
        assertTrue(NbtGzip.isRaw(RAW));
        assertFalse(NbtGzip.isGzip(RAW));
        assertFalse(NbtGzip.isGzip(new byte[0]));
        assertFalse(NbtGzip.isRaw(new byte[0]));
    }

    @Test
    public void convertsBetweenTheForms() {
        byte[] gzip = NbtGzip.gzip(RAW);
        assertArrayEquals(RAW, NbtGzip.raw(gzip));
        assertSame(RAW, NbtGzip.raw(RAW));
        assertArrayEquals(RAW, NbtGzip.gunzip(NbtGzip.gzipped(RAW)));
        assertSame(gzip, NbtGzip.gzipped(gzip));
    }

    @Test
    public void passesOtherFormatsThrough() {
        byte[] javaSerialized = {(byte) 0xAC, (byte) 0xED, 0, 5};
        assertSame(javaSerialized, NbtGzip.raw(javaSerialized));
        assertSame(javaSerialized, NbtGzip.gzipped(javaSerialized));
    }

    @Test
    public void rejectsCorruptGzip() {
        byte[] gzip = NbtGzip.gzip(RAW);
        assertThrows(CodecException.class, () -> NbtGzip.raw(Arrays.copyOf(gzip, gzip.length / 2)));
    }

    @Test
    public void refusesABomb() {
        byte[] bomb = NbtGzip.gzip(new byte[NbtGzip.MAX_INFLATED_BYTES + 1]);
        assertThrows(CodecException.class, () -> NbtGzip.gunzip(bomb));
    }
}
