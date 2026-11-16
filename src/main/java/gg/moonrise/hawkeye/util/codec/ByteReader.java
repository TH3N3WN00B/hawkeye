package gg.moonrise.hawkeye.util.codec;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.jetbrains.annotations.NotNull;

/** Reads what {@link ByteWriter} wrote. Every read checks bounds and throws {@link CodecException}. */
public final class ByteReader {

    private final byte[] bytes;
    private final int end;
    private int offset;

    /**
     * A reader over a whole array.
     *
     * @param bytes the encoded bytes
     */
    public ByteReader(byte @NotNull [] bytes) {
        this(bytes, 0, bytes.length);
    }

    /**
     * A reader over a slice of an array.
     *
     * @param bytes  the array
     * @param offset where the slice starts
     * @param length how many bytes it holds
     * @throws CodecException when the slice is outside the array
     */
    public ByteReader(byte @NotNull [] bytes, int offset, int length) {
        if (offset < 0 || length < 0 || offset > bytes.length - length) {
            throw new CodecException("slice " + offset + "+" + length + " outside " + bytes.length + " byte(s)");
        }
        this.bytes = bytes;
        this.offset = offset;
        this.end = offset + length;
    }

    /**
     * How many bytes remain.
     *
     * @return the remaining byte count
     */
    public int remaining() {
        return end - offset;
    }

    /**
     * Whether bytes remain.
     *
     * @return {@code true} when the end has not been reached
     */
    public boolean hasRemaining() {
        return offset < end;
    }

    /**
     * Reads one unsigned byte.
     *
     * @return 0 to 255
     */
    public int readByte() {
        require(1);
        return bytes[offset++] & 0xFF;
    }

    /**
     * Reads an unsigned variable-length integer.
     *
     * @return the value
     */
    public long readVarLong() {
        int at = offset;
        if (at < end && bytes[at] >= 0) {
            offset = at + 1;
            return bytes[at];
        }
        long value = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            int b = readByte();
            value |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        throw new CodecException("varint longer than 10 bytes");
    }

    /**
     * Reads a variable-length integer that must fit an {@code int}.
     *
     * @return the value
     */
    public int readVarInt() {
        long value = readVarLong();
        if (value > Integer.MAX_VALUE) {
            throw new CodecException("varint does not fit an int: " + value);
        }
        return (int) value;
    }

    /**
     * Reads a zig-zag encoded signed value.
     *
     * @return the value
     */
    public long readZigZag() {
        long raw = readVarLong();
        return (raw >>> 1) ^ -(raw & 1);
    }

    /**
     * Reads a fixed 8-byte big-endian long.
     *
     * @return the value
     */
    public long readLong() {
        require(8);
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (bytes[offset++] & 0xFF);
        }
        return value;
    }

    /**
     * Reads a length-prefixed byte array.
     *
     * @return the bytes
     */
    public byte @NotNull [] readBytes() {
        int length = readVarInt();
        require(length);
        byte[] out = Arrays.copyOfRange(bytes, offset, offset + length);
        offset += length;
        return out;
    }

    /**
     * Reads a number of raw bytes without a length prefix.
     *
     * @param length how many bytes
     * @return the bytes
     */
    public byte @NotNull [] readRaw(int length) {
        require(length);
        byte[] out = Arrays.copyOfRange(bytes, offset, offset + length);
        offset += length;
        return out;
    }

    /**
     * Reads a length-prefixed UTF-8 string.
     *
     * @return the text
     */
    public @NotNull String readString() {
        return new String(readBytes(), StandardCharsets.UTF_8);
    }

    /**
     * Skips bytes.
     *
     * @param count how many bytes
     */
    public void skip(int count) {
        require(count);
        offset += count;
    }

    /**
     * Reads every remaining byte.
     *
     * @return the bytes
     */
    public byte @NotNull [] readRemaining() {
        byte[] out = Arrays.copyOfRange(bytes, offset, end);
        offset = end;
        return out;
    }

    private void require(int count) {
        if (count < 0 || count > end - offset) {
            throw new CodecException("truncated input: need " + count + " byte(s) at " + offset + " of "
                    + end);
        }
    }
}
