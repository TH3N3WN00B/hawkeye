package gg.moonrise.hawkeye.util.codec;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.jetbrains.annotations.NotNull;

/** A growable byte buffer with unsigned LEB128 variable-length integers and length-prefixed fields. */
public final class ByteWriter {

    private final ByteArrayOutputStream out;

    /**
     * A writer with an initial capacity.
     *
     * @param capacity the initial buffer size
     */
    public ByteWriter(int capacity) {
        this.out = new ByteArrayOutputStream(capacity);
    }

    /**
     * Writes one byte.
     *
     * @param value the byte (the low 8 bits are written)
     * @return this writer
     */
    public @NotNull ByteWriter writeByte(int value) {
        out.write(value);
        return this;
    }

    /**
     * Writes an unsigned variable-length integer (1 byte below 128).
     *
     * @param value a non-negative value
     * @return this writer
     */
    public @NotNull ByteWriter writeVarLong(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("negative varint: " + value);
        }
        long rest = value;
        while ((rest & ~0x7FL) != 0) {
            out.write((int) ((rest & 0x7F) | 0x80));
            rest >>>= 7;
        }
        out.write((int) rest);
        return this;
    }

    /**
     * Writes a signed value with zig-zag encoding, so small negative numbers stay short.
     *
     * @param value any value
     * @return this writer
     */
    public @NotNull ByteWriter writeZigZag(long value) {
        return writeVarLong((value << 1) ^ (value >> 63));
    }

    /**
     * Writes a fixed 8-byte big-endian long.
     *
     * @param value the value
     * @return this writer
     */
    public @NotNull ByteWriter writeLong(long value) {
        for (int shift = 56; shift >= 0; shift -= 8) {
            out.write((int) (value >>> shift));
        }
        return this;
    }

    /**
     * Writes a length-prefixed byte array.
     *
     * @param bytes the bytes
     * @return this writer
     */
    public @NotNull ByteWriter writeBytes(byte @NotNull [] bytes) {
        writeVarLong(bytes.length);
        out.writeBytes(bytes);
        return this;
    }

    /**
     * Writes a length-prefixed UTF-8 string.
     *
     * @param text the text
     * @return this writer
     */
    public @NotNull ByteWriter writeString(@NotNull String text) {
        return writeBytes(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Writes raw bytes without a length.
     *
     * @param bytes the bytes
     * @return this writer
     */
    public @NotNull ByteWriter writeRaw(byte @NotNull [] bytes) {
        out.writeBytes(bytes);
        return this;
    }

    /**
     * The bytes written so far.
     *
     * @return a copy of the buffer
     */
    public byte @NotNull [] toByteArray() {
        return out.toByteArray();
    }
}
