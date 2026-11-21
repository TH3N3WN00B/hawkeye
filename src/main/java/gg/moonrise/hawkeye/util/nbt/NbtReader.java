package gg.moonrise.hawkeye.util.nbt;

import gg.moonrise.hawkeye.util.codec.binary.CodecException;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.NotNull;

/**
 * Reads uncompressed binary NBT. Every length is checked against the bytes that remain before
 * anything is allocated, nesting is limited to {@link #MAX_DEPTH} (Minecraft's own limit), and any
 * malformed input throws {@link CodecException}.
 */
public final class NbtReader {

    /** The deepest nesting of lists and compounds accepted. */
    public static final int MAX_DEPTH = 512;

    private final byte @NotNull [] bytes;
    private int position;

    private NbtReader(byte @NotNull [] bytes) {
        this.bytes = bytes;
    }

    /**
     * Reads a document: a compound tag id, the root name and the compound; nothing may follow.
     *
     * @param bytes uncompressed binary NBT
     * @return the root
     * @throws CodecException when the bytes are not valid NBT
     */
    public static @NotNull NbtRoot read(byte @NotNull [] bytes) {
        NbtReader reader = new NbtReader(bytes);
        int id = reader.readUnsignedByte();
        if (id != NbtTag.COMPOUND) {
            throw new CodecException("NBT root is tag " + id + ", not a compound");
        }
        String name = reader.readString();
        NbtTag.NbtCompound compound = reader.readCompound(0);
        if (reader.position != bytes.length) {
            throw new CodecException((bytes.length - reader.position) + " bytes after the NBT root");
        }
        return new NbtRoot(name, compound);
    }

    private @NotNull NbtTag readPayload(int id, int depth) {
        return switch (id) {
            case NbtTag.BYTE -> new NbtTag.NbtByte((byte) readUnsignedByte());
            case NbtTag.SHORT -> new NbtTag.NbtShort((short) readUnsignedShort());
            case NbtTag.INT -> new NbtTag.NbtInt(readInt());
            case NbtTag.LONG -> new NbtTag.NbtLong(readLong());
            case NbtTag.FLOAT -> new NbtTag.NbtFloat(readInt());
            case NbtTag.DOUBLE -> new NbtTag.NbtDouble(readLong());
            case NbtTag.BYTE_ARRAY -> {
                int length = readLength(1);
                byte[] values = new byte[length];
                System.arraycopy(bytes, position, values, 0, length);
                position += length;
                yield new NbtTag.NbtByteArray(values);
            }
            case NbtTag.STRING -> new NbtTag.NbtString(readString());
            case NbtTag.LIST -> readList(depth + 1);
            case NbtTag.COMPOUND -> readCompound(depth + 1);
            case NbtTag.INT_ARRAY -> {
                int[] values = new int[readLength(Integer.BYTES)];
                for (int i = 0; i < values.length; i++) {
                    values[i] = readInt();
                }
                yield new NbtTag.NbtIntArray(values);
            }
            case NbtTag.LONG_ARRAY -> {
                long[] values = new long[readLength(Long.BYTES)];
                for (int i = 0; i < values.length; i++) {
                    values[i] = readLong();
                }
                yield new NbtTag.NbtLongArray(values);
            }
            default -> throw new CodecException("unknown NBT tag " + id + " at byte " + (position - 1));
        };
    }

    private @NotNull NbtTag.NbtList readList(int depth) {
        checkDepth(depth);
        int elementType = readUnsignedByte();
        if (elementType > NbtTag.LONG_ARRAY) {
            throw new CodecException("unknown NBT list element type " + elementType);
        }
        int count = readInt();
        if (count < 0) {
            throw new CodecException("negative NBT list length " + count);
        }
        if (elementType == NbtTag.END && count > 0) {
            throw new CodecException("NBT list of " + count + " untyped elements");
        }
        if ((long) count * minimumSize(elementType) > remaining()) {
            throw new CodecException("NBT list of " + count + " elements exceeds the input");
        }
        List<NbtTag> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            values.add(readPayload(elementType, depth));
        }
        return new NbtTag.NbtList(elementType, values);
    }

    private @NotNull NbtTag.NbtCompound readCompound(int depth) {
        checkDepth(depth);
        Map<String, NbtTag> values = new LinkedHashMap<>();
        while (true) {
            int id = readUnsignedByte();
            if (id == NbtTag.END) {
                return new NbtTag.NbtCompound(values);
            }
            String name = readString();
            if (values.putIfAbsent(name, readPayload(id, depth)) != null) {
                throw new CodecException("duplicate NBT key " + name);
            }
        }
    }

    private static void checkDepth(int depth) {
        if (depth > MAX_DEPTH) {
            throw new CodecException("NBT nested deeper than " + MAX_DEPTH);
        }
    }

    /** The fewest bytes one element of a type takes, so a list length can be checked up front. */
    private static int minimumSize(int id) {
        return switch (id) {
            case NbtTag.BYTE, NbtTag.COMPOUND -> 1;
            case NbtTag.SHORT, NbtTag.STRING -> 2;
            case NbtTag.INT, NbtTag.FLOAT, NbtTag.BYTE_ARRAY, NbtTag.INT_ARRAY, NbtTag.LONG_ARRAY -> 4;
            case NbtTag.LIST -> 5;
            case NbtTag.LONG, NbtTag.DOUBLE -> 8;
            default -> 0;
        };
    }

    private @NotNull String readString() {
        int length = readUnsignedShort();
        require(length);
        try {
            // Modified UTF-8, exactly what DataOutput#writeUTF writes back.
            String value = new DataInputStream(new ByteArrayInputStream(bytes, position - 2, length + 2)).readUTF();
            position += length;
            return value;
        } catch (IOException e) {
            throw new CodecException("invalid NBT string at byte " + position, e);
        }
    }

    private int readLength(int elementSize) {
        int length = readInt();
        if (length < 0 || (long) length * elementSize > remaining()) {
            throw new CodecException("NBT array length " + length + " exceeds the input");
        }
        return length;
    }

    private int readUnsignedByte() {
        require(1);
        return bytes[position++] & 0xFF;
    }

    private int readUnsignedShort() {
        require(2);
        int value = (bytes[position] & 0xFF) << 8 | bytes[position + 1] & 0xFF;
        position += 2;
        return value;
    }

    private int readInt() {
        require(4);
        int value = (bytes[position] & 0xFF) << 24 | (bytes[position + 1] & 0xFF) << 16
                | (bytes[position + 2] & 0xFF) << 8 | bytes[position + 3] & 0xFF;
        position += 4;
        return value;
    }

    private long readLong() {
        long high = readInt() & 0xFFFFFFFFL;
        return high << 32 | readInt() & 0xFFFFFFFFL;
    }

    private int remaining() {
        return bytes.length - position;
    }

    private void require(int count) {
        if (count > remaining()) {
            throw new CodecException("NBT truncated at byte " + position);
        }
    }
}
