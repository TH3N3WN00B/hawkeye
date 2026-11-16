package gg.moonrise.hawkeye.core.repository.segment;

import gg.moonrise.hawkeye.util.codec.ByteReader;
import gg.moonrise.hawkeye.util.codec.CodecException;
import gg.moonrise.hawkeye.util.codec.Compression;
import java.util.Arrays;
import org.jetbrains.annotations.NotNull;

/**
 * A segment's decoded palette frame ({@link SharedFrame#PALETTES}): what a row's actor, subject and
 * secondary columns index. A read whose filter names actors or types needs it before it can match a
 * unit's columns; it is small and fetched by itself ({@link SegmentHeader#palettes()}).
 *
 * @param actors the actor ids, most frequent first
 * @param types  the subject and secondary type ids, most frequent first
 */
public record Palettes(int @NotNull [] actors, int @NotNull [] types) {

    /**
     * Decodes the palette frame.
     *
     * @param header the segment header
     * @param bytes  an array holding the frame
     * @param offset where it starts in it
     * @param length its length; must equal {@code header.palettes().length()}
     * @return the palettes
     * @throws CodecException when the frame is corrupt
     */
    public static @NotNull Palettes decode(@NotNull SegmentHeader header, byte @NotNull [] bytes, int offset,
            int length) {
        if (length != header.palettes().length() || offset < 0 || offset > bytes.length - length) {
            throw new CodecException("the palette frame is " + length + " bytes, the header says "
                    + header.palettes().length());
        }
        ByteReader in = new ByteReader(Compression.decompress(bytes, offset, length));
        int[] actors = read(in);
        int[] types = read(in);
        if (in.hasRemaining()) {
            throw new CodecException(in.remaining() + " unread byte(s) in the palette column");
        }
        if (actors.length != header.actorIds().length) {
            throw new CodecException("the actor palette does not match the header's actor list");
        }
        return new Palettes(actors, types);
    }

    /**
     * The approximate heap size, for cache weights.
     *
     * @return bytes
     */
    public int weight() {
        return (actors.length + types.length) * Integer.BYTES + 48;
    }

    private static int @NotNull [] read(@NotNull ByteReader in) {
        int count = in.readVarInt();
        if (count > in.remaining()) {
            throw new CodecException("palette of " + count + " exceeds its frame");
        }
        int[] values = new int[count];
        for (int i = 0; i < count; i++) {
            values[i] = in.readVarInt();
        }
        return values;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Palettes palettes && Arrays.equals(actors, palettes.actors)
                && Arrays.equals(types, palettes.types);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(actors) * 31 + Arrays.hashCode(types);
    }

    @Override
    public @NotNull String toString() {
        return "Palettes[actors=" + actors.length + ", types=" + types.length + "]";
    }
}
