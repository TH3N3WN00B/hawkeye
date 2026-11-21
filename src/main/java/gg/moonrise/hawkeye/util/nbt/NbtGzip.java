package gg.moonrise.hawkeye.util.nbt;

import gg.moonrise.hawkeye.util.codec.binary.CodecException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.jetbrains.annotations.NotNull;

/**
 * The two forms of NBT that Paper and Hawkeye exchange: gzip (what {@code ItemStack#serializeAsBytes}
 * and {@code UnsafeValues#serializeEntity} return and their readers expect) and raw (what Hawkeye
 * stores, so the archive's compression sees the repetition between snapshots).
 */
public final class NbtGzip {

    /** Refuses NBT that inflates beyond this (a corrupt or hostile row). */
    public static final int MAX_INFLATED_BYTES = 16 * 1024 * 1024;

    private static final int GZIP_MAGIC_0 = 0x1f;
    private static final int GZIP_MAGIC_1 = 0x8b;

    private NbtGzip() {
    }

    /**
     * Whether bytes are gzip-compressed.
     *
     * @param bytes the bytes
     * @return {@code true} when they start with the gzip magic number
     */
    public static boolean isGzip(byte @NotNull [] bytes) {
        return bytes.length >= 2 && (bytes[0] & 0xFF) == GZIP_MAGIC_0 && (bytes[1] & 0xFF) == GZIP_MAGIC_1;
    }

    /**
     * Whether bytes look like raw binary NBT with a compound root.
     *
     * @param bytes the bytes
     * @return {@code true} when the first byte is the compound tag id
     */
    public static boolean isRaw(byte @NotNull [] bytes) {
        return bytes.length > 0 && bytes[0] == NbtTag.COMPOUND;
    }

    /**
     * The raw form of NBT in either form. Bytes that are not gzip are returned as they are (raw NBT, or
     * another server implementation's own format, which only that implementation reads back).
     *
     * @param bytes gzip or raw NBT
     * @return the decompressed bytes, or the same array when it was not gzip
     * @throws CodecException when the gzip stream is corrupt
     */
    public static byte @NotNull [] raw(byte @NotNull [] bytes) {
        return isGzip(bytes) ? gunzip(bytes) : bytes;
    }

    /**
     * The gzip form Paper reads. Raw NBT is gzipped; anything else (already gzip, or another server
     * implementation's own format) is returned as it is.
     *
     * @param bytes gzip or raw NBT
     * @return gzip NBT, or the same array when it was not raw NBT
     */
    public static byte @NotNull [] gzipped(byte @NotNull [] bytes) {
        return isRaw(bytes) ? gzip(bytes) : bytes;
    }

    /**
     * Compresses bytes with gzip.
     *
     * @param bytes the bytes
     * @return the gzip stream
     */
    public static byte @NotNull [] gzip(byte @NotNull [] bytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length / 2 + 32);
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(bytes);
        } catch (IOException e) {
            throw new IllegalStateException("in-memory gzip failed", e);
        }
        return out.toByteArray();
    }

    /**
     * Decompresses a gzip stream, refusing output beyond {@link #MAX_INFLATED_BYTES}.
     *
     * @param bytes the gzip stream
     * @return the decompressed bytes
     * @throws CodecException when the stream is corrupt, truncated or too large
     */
    public static byte @NotNull [] gunzip(byte @NotNull [] bytes) {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length * 4 + 64);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                if (out.size() > MAX_INFLATED_BYTES) {
                    throw new CodecException("NBT inflates beyond " + MAX_INFLATED_BYTES + " bytes");
                }
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new CodecException("corrupt gzip NBT", e);
        }
    }
}
