package gg.moonrise.hawkeye.util.nbt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import gg.moonrise.hawkeye.util.codec.binary.CodecException;
import gg.moonrise.hawkeye.util.nbt.NbtTag.NbtByte;
import gg.moonrise.hawkeye.util.nbt.NbtTag.NbtByteArray;
import gg.moonrise.hawkeye.util.nbt.NbtTag.NbtCompound;
import gg.moonrise.hawkeye.util.nbt.NbtTag.NbtDouble;
import gg.moonrise.hawkeye.util.nbt.NbtTag.NbtFloat;
import gg.moonrise.hawkeye.util.nbt.NbtTag.NbtInt;
import gg.moonrise.hawkeye.util.nbt.NbtTag.NbtIntArray;
import gg.moonrise.hawkeye.util.nbt.NbtTag.NbtList;
import gg.moonrise.hawkeye.util.nbt.NbtTag.NbtLong;
import gg.moonrise.hawkeye.util.nbt.NbtTag.NbtLongArray;
import gg.moonrise.hawkeye.util.nbt.NbtTag.NbtShort;
import gg.moonrise.hawkeye.util.nbt.NbtTag.NbtString;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class NbtReaderTest {

    @Test
    public void roundTripsEveryTagType() {
        NbtRoot root = new NbtRoot("", compound(
                "byte", new NbtByte((byte) -3),
                "short", new NbtShort((short) 31000),
                "int", new NbtInt(-123456789),
                "long", new NbtLong(Long.MIN_VALUE + 7),
                "float", NbtFloat.of(1.5f),
                "nan", new NbtFloat(0x7fc00001),
                "double", NbtDouble.of(-0.0),
                "bytes", new NbtByteArray(new byte[] {1, -1, 127}),
                "string", new NbtString("Grüße \u0000 😀"),
                "ints", new NbtIntArray(new int[] {Integer.MIN_VALUE, 0, 9}),
                "longs", new NbtLongArray(new long[] {1L, -2L}),
                "list", NbtList.of(NbtTag.STRING, new NbtString("a"), new NbtString("b")),
                "compounds", NbtList.of(NbtTag.COMPOUND,
                        compound("id", new NbtString("minecraft:x"), "nested", NbtList.of(NbtTag.LIST,
                                NbtList.of(NbtTag.INT, new NbtInt(1)), NbtList.of(NbtTag.END))),
                        compound()),
                "emptyUntyped", NbtList.of(NbtTag.END),
                "emptyTyped", NbtList.of(NbtTag.COMPOUND),
                "child", compound("deeper", compound("deepest", new NbtByte((byte) 1)))));
        byte[] written = root.write();
        NbtRoot read = NbtRoot.read(written);
        assertEquals(root, read);
        assertArrayEquals(written, read.write());
    }

    @Test
    public void keepsInsertionOrderAndTheRootName() {
        NbtRoot root = new NbtRoot("named", compound("z", new NbtByte((byte) 1), "a", new NbtByte((byte) 2),
                "m", new NbtByte((byte) 3)));
        NbtRoot read = NbtRoot.read(root.write());
        assertEquals("named", read.name());
        assertEquals(List.of("z", "a", "m"), List.copyOf(read.compound().values().keySet()));
    }

    @Test
    public void readsBytesWrittenByAStandardEncoder() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buffer);
        out.writeByte(10);
        out.writeUTF("");
        out.writeByte(8);
        out.writeUTF("id");
        out.writeUTF("minecraft:zombie");
        out.writeByte(9);
        out.writeUTF("Pos");
        out.writeByte(6);
        out.writeInt(2);
        out.writeDouble(1.5);
        out.writeDouble(-60);
        out.writeByte(0);
        byte[] bytes = buffer.toByteArray();
        NbtRoot root = NbtRoot.read(bytes);
        assertEquals(new NbtString("minecraft:zombie"), root.compound().get("id").orElseThrow());
        assertEquals(-60.0, ((NbtDouble) ((NbtList) root.compound().get("Pos").orElseThrow()).values().get(1))
                .value());
        assertArrayEquals(bytes, root.write());
    }

    @Test
    public void rejectsTruncatedInputAtEveryLength() {
        byte[] bytes = new NbtRoot("", compound("list", NbtList.of(NbtTag.COMPOUND, compound("s",
                new NbtString("hello"), "l", new NbtLongArray(new long[] {1, 2})))))
                .write();
        for (int length = 0; length < bytes.length; length++) {
            byte[] truncated = Arrays.copyOf(bytes, length);
            assertThrows(CodecException.class, () -> NbtRoot.read(truncated), "length " + length);
        }
    }

    @Test
    public void rejectsCorruptInput() {
        assertThrows(CodecException.class, () -> NbtRoot.read(new byte[] {8, 0, 0, 0, 0}));
        // An unknown tag id.
        assertThrows(CodecException.class, () -> NbtRoot.read(new byte[] {10, 0, 0, 13, 0, 1, 'x', 0}));
        // A list of 2^31-1 ints in a few bytes: refused before anything is allocated.
        assertThrows(CodecException.class, () -> NbtRoot.read(new byte[] {10, 0, 0, 9, 0, 1, 'l', 3, 0x7f, -1, -1,
                -1, 0}));
        // A byte array with a negative length.
        assertThrows(CodecException.class, () -> NbtRoot.read(new byte[] {10, 0, 0, 7, 0, 1, 'b', -1, -1, -1, -1,
                0}));
        // An untyped list that claims elements.
        assertThrows(CodecException.class, () -> NbtRoot.read(new byte[] {10, 0, 0, 9, 0, 1, 'l', 0, 0, 0, 0, 1,
                0}));
        // Bytes after the root.
        assertThrows(CodecException.class, () -> NbtRoot.read(new byte[] {10, 0, 0, 0, 0}));
        // A duplicate key.
        assertThrows(CodecException.class, () -> NbtRoot.read(new byte[] {10, 0, 0, 1, 0, 1, 'k', 1, 1, 0, 1, 'k',
                2, 0}));
        // Invalid modified UTF-8.
        assertThrows(CodecException.class, () -> NbtRoot.read(new byte[] {10, 0, 0, 8, 0, 1, 's', 0, 1, (byte) 0xC0,
                0}));
    }

    @Test
    public void limitsNesting() {
        NbtTag nested = compound();
        for (int depth = 0; depth < NbtReader.MAX_DEPTH; depth++) {
            nested = compound("c", nested);
        }
        byte[] atLimit = new NbtRoot("", (NbtCompound) nested).write();
        assertEquals(new NbtRoot("", (NbtCompound) nested), NbtRoot.read(atLimit));
        byte[] tooDeep = new NbtRoot("", compound("c", nested)).write();
        assertThrows(CodecException.class, () -> NbtRoot.read(tooDeep));
    }

    @Test
    public void listsRejectElementsOfAnotherType() {
        assertThrows(IllegalArgumentException.class, () -> NbtList.of(NbtTag.INT, new NbtByte((byte) 1)));
    }

    public static NbtCompound compound(Object... entries) {
        Map<String, NbtTag> values = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            values.put((String) entries[i], (NbtTag) entries[i + 1]);
        }
        return new NbtCompound(values);
    }
}
