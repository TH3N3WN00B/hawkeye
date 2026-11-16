package gg.moonrise.hawkeye.util.codec;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.util.Native;
import java.io.ByteArrayOutputStream;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

/**
 * Self-describing compressed frames: {@code [codec byte][varint raw length][bytes]}. The writer uses
 * zstd level 19 when the native library loads and raw deflate level 9 otherwise, and stores the bytes
 * as they are when compressing does not make them smaller. A reader accepts all three codecs.
 */
@Slf4j
public final class Compression {

    /** Codec byte: the bytes are stored as they are. */
    public static final int STORED = 0;

    /** Codec byte: a zstd frame. */
    public static final int ZSTD = 1;

    /** Codec byte: a raw deflate stream (no zlib header). */
    public static final int DEFLATE = 2;

    /** Decompression refuses frames that state a raw length above this. */
    public static final int MAX_RAW_BYTES = 64 * 1024 * 1024;

    private static final int ZSTD_LEVEL = 19;

    private Compression() {
    }

    /**
     * Whether the zstd native library loads on this host. Probed once; a failure is logged once.
     *
     * @return {@code true} when zstd can be used
     */
    public static boolean zstdAvailable() {
        return ZstdProbe.AVAILABLE;
    }

    /**
     * Compresses bytes into a frame with the preferred codec: zstd when available, else deflate.
     *
     * @param raw the bytes
     * @return the frame
     */
    public static byte @NotNull [] compress(byte @NotNull [] raw) {
        return compress(raw, zstdAvailable() ? ZSTD : DEFLATE);
    }

    /**
     * Compresses bytes into a frame with the preferred codec at a chosen zstd level (deflate level 9
     * when zstd is not available): for small rows rewritten often, where level 19 costs more time than
     * it saves bytes.
     *
     * @param raw   the bytes
     * @param level the zstd level, 1 to 22
     * @return the frame
     */
    public static byte @NotNull [] compressAt(byte @NotNull [] raw, int level) {
        if (!zstdAvailable() || raw.length > MAX_RAW_BYTES) {
            return compress(raw, DEFLATE);
        }
        return frame(raw, ZSTD, Zstd.compress(raw, level));
    }

    /**
     * Compresses bytes into a frame with a given codec, falling back to {@link #STORED} when the codec
     * does not make them smaller.
     *
     * @param raw   the bytes
     * @param codec {@link #STORED}, {@link #ZSTD} or {@link #DEFLATE}
     * @return the frame
     * @throws IllegalArgumentException when the codec is unknown, or zstd is asked for and not available
     */
    public static byte @NotNull [] compress(byte @NotNull [] raw, int codec) {
        if (raw.length > MAX_RAW_BYTES) {
            throw new IllegalArgumentException("frame of " + raw.length + " bytes exceeds " + MAX_RAW_BYTES);
        }
        byte[] packed = switch (codec) {
            case STORED -> raw;
            case ZSTD -> {
                if (!zstdAvailable()) {
                    throw new IllegalArgumentException("zstd is not available on this host");
                }
                yield Zstd.compress(raw, ZSTD_LEVEL);
            }
            case DEFLATE -> deflate(raw);
            default -> throw new IllegalArgumentException("unknown codec " + codec);
        };
        return frame(raw, codec, packed);
    }

    /** The frame of compressed bytes, or of the raw ones when compressing did not make them smaller. */
    private static byte @NotNull [] frame(byte @NotNull [] raw, int codec, byte @NotNull [] packed) {
        int used = codec;
        byte[] stored = packed;
        if (stored.length >= raw.length) {
            stored = raw;
            used = STORED;
        }
        ByteWriter out = new ByteWriter(stored.length + 6);
        out.writeByte(used).writeVarLong(raw.length).writeRaw(stored);
        return out.toByteArray();
    }

    /**
     * Decompresses one frame.
     *
     * @param frame  the array holding the frame
     * @param offset where the frame starts
     * @param length the frame's length
     * @return the raw bytes
     * @throws CodecException when the frame is corrupt, truncated, too large, of an unknown codec, or a
     *                        zstd frame on a host without zstd
     */
    public static byte @NotNull [] decompress(byte @NotNull [] frame, int offset, int length) {
        ByteReader in = new ByteReader(frame, offset, length);
        int codec = in.readByte();
        long stated = in.readVarLong();
        if (stated > MAX_RAW_BYTES) {
            throw new CodecException("frame states " + stated + " raw bytes, above " + MAX_RAW_BYTES);
        }
        int rawLength = (int) stated;
        int start = offset + length - in.remaining();
        int packed = in.remaining();
        return switch (codec) {
            case STORED -> {
                if (packed != rawLength) {
                    throw new CodecException("stored frame holds " + packed + " bytes, states " + rawLength);
                }
                yield in.readRaw(packed);
            }
            case ZSTD -> unzstd(frame, start, packed, rawLength);
            case DEFLATE -> inflate(frame, start, packed, rawLength);
            default -> throw new CodecException("unknown compression codec " + codec);
        };
    }

    private static byte @NotNull [] unzstd(byte @NotNull [] frame, int start, int packed, int rawLength) {
        if (!zstdAvailable()) {
            throw new CodecException("a zstd frame cannot be read: the zstd native library does not load here");
        }
        byte[] out = new byte[rawLength];
        long result;
        try {
            result = Zstd.decompressByteArray(out, 0, rawLength, frame, start, packed);
        } catch (RuntimeException e) {
            throw new CodecException("corrupt zstd frame", e);
        }
        if (Zstd.isError(result)) {
            throw new CodecException("corrupt zstd frame: " + Zstd.getErrorName(result));
        }
        if (result != rawLength) {
            throw new CodecException("zstd frame holds " + result + " bytes, states " + rawLength);
        }
        return out;
    }

    private static byte @NotNull [] deflate(byte @NotNull [] raw) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, true);
        try {
            deflater.setInput(raw);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length / 3 + 64);
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                out.write(buffer, 0, deflater.deflate(buffer));
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private static byte @NotNull [] inflate(byte @NotNull [] frame, int start, int packed, int rawLength) {
        Inflater inflater = new Inflater(true);
        try {
            inflater.setInput(frame, start, packed);
            byte[] out = new byte[rawLength];
            int filled = 0;
            while (filled < rawLength) {
                int read = inflater.inflate(out, filled, rawLength - filled);
                filled += read;
                if (read == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
            }
            if (filled != rawLength) {
                throw new CodecException("deflate frame holds " + filled + " bytes, states " + rawLength);
            }
            if (!inflater.finished() && inflater.inflate(new byte[1]) > 0) {
                throw new CodecException("deflate frame holds more than the stated " + rawLength + " bytes");
            }
            if (!inflater.finished()) {
                throw new CodecException("truncated deflate frame");
            }
            return out;
        } catch (DataFormatException e) {
            throw new CodecException("corrupt deflate frame", e);
        } finally {
            inflater.end();
        }
    }

    /** Loads the native library on first use (class initialization runs once and is thread-safe). */
    private static final class ZstdProbe {

        private static final boolean AVAILABLE = probe();

        private ZstdProbe() {
        }

        private static boolean probe() {
            try {
                Native.load();
                return Native.isLoaded() && Zstd.compressBound(1) > 0;
            } catch (Throwable e) {
                log.warn("The zstd native library does not load ({}); archive frames use deflate instead", e.toString());
                return false;
            }
        }
    }
}
