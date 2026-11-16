package gg.moonrise.hawkeye.core.repository;

import gg.moonrise.hawkeye.util.codec.ByteReader;
import gg.moonrise.hawkeye.util.codec.ByteWriter;
import gg.moonrise.hawkeye.util.codec.CodecException;
import java.util.Arrays;
import org.jetbrains.annotations.NotNull;

/**
 * The {@code segment_flag.ordinals} value: the ascending ordinals of a segment's rolled-back rows, each
 * stored as the varint of its distance from the previous one (the first from -1), so a run of
 * neighbouring rows costs a byte each.
 */
public final class FlagOrdinals {

    private FlagOrdinals() {
    }

    /**
     * Encodes ordinals.
     *
     * @param ordinals strictly ascending, non-negative ordinals
     * @return the bytes
     * @throws IllegalArgumentException when they are not strictly ascending
     */
    public static byte @NotNull [] encode(int @NotNull [] ordinals) {
        ByteWriter out = new ByteWriter(ordinals.length + 8);
        int previous = -1;
        for (int ordinal : ordinals) {
            if (ordinal <= previous) {
                throw new IllegalArgumentException("ordinals must be strictly ascending and not negative");
            }
            out.writeVarLong(ordinal - previous);
            previous = ordinal;
        }
        return out.toByteArray();
    }

    /**
     * Decodes ordinals.
     *
     * @param bytes the stored value
     * @return the ordinals, ascending
     * @throws CodecException when the bytes are corrupt
     */
    public static int @NotNull [] decode(byte @NotNull [] bytes) {
        ByteReader in = new ByteReader(bytes);
        int[] ordinals = new int[bytes.length];
        int count = 0;
        long previous = -1;
        while (in.hasRemaining()) {
            long delta = in.readVarLong();
            if (delta < 1 || previous + delta > Integer.MAX_VALUE) {
                throw new CodecException("corrupt rolled-back ordinals");
            }
            previous += delta;
            ordinals[count++] = (int) previous;
        }
        return count == ordinals.length ? ordinals : Arrays.copyOf(ordinals, count);
    }
}
