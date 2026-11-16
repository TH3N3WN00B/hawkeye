package gg.moonrise.hawkeye.core.repository.segment;

import gg.moonrise.hawkeye.util.EventIds;
import gg.moonrise.hawkeye.util.codec.ByteReader;
import gg.moonrise.hawkeye.util.codec.CodecException;
import gg.moonrise.hawkeye.util.codec.Compression;
import org.jetbrains.annotations.NotNull;

/**
 * The decoded shared frames of a segment: every row's id by ordinal, and the two palettes. Decode once
 * per segment and reuse it for any number of units ({@link Segments#decodeUnits}).
 */
public final class SharedColumns {

    private final long[] ids;
    private final int[] actorPalette;
    private final int[] typePalette;

    private SharedColumns(long @NotNull [] ids, int @NotNull [] actorPalette, int @NotNull [] typePalette) {
        this.ids = ids;
        this.actorPalette = actorPalette;
        this.typePalette = typePalette;
    }

    /**
     * Decodes the shared frames.
     *
     * @param header the segment header
     * @param bytes  an array holding the shared frames
     * @param offset where they start in it
     * @param length their length; must equal {@code header.shared().length()}
     * @return the decoded columns
     * @throws CodecException when the frames are corrupt
     */
    public static @NotNull SharedColumns decode(@NotNull SegmentHeader header, byte @NotNull [] bytes, int offset,
            int length) {
        if (length != header.shared().length() || offset < 0 || offset > bytes.length - length) {
            throw new CodecException("shared frames are " + length + " bytes, the header says "
                    + header.shared().length());
        }
        int events = header.events();
        int at = offset;
        byte[] time = frame(bytes, at, header.frameLength(SharedFrame.TIME));
        at += header.frameLength(SharedFrame.TIME);
        byte[] node = frame(bytes, at, header.frameLength(SharedFrame.NODE));
        at += header.frameLength(SharedFrame.NODE);
        byte[] rank = frame(bytes, at, header.frameLength(SharedFrame.RANK));
        at += header.frameLength(SharedFrame.RANK);
        byte[] palettes = frame(bytes, at, header.frameLength(SharedFrame.PALETTES));
        if (time.length < events || node.length > 0 && node.length < events || rank.length > 0 && rank.length < events) {
            throw new CodecException("a shared column is shorter than the " + events + " rows");
        }
        long[] slots = readSlots(new ByteReader(time), header);
        int[] nodes = header.nodeIds();
        int[] nodeIndexes = new int[events];
        if (node.length > 0) {
            ByteReader in = new ByteReader(node);
            for (int i = 0; i < events; i++) {
                long index = in.readVarLong();
                if (index >= nodes.length) {
                    throw new CodecException("node index " + index + " out of " + nodes.length);
                }
                nodeIndexes[i] = (int) index;
            }
            finish(in, "node");
        }
        long[] ids = readIds(header, slots, nodeIndexes, rank);
        ByteReader in = new ByteReader(palettes);
        int[] actorPalette = readPalette(in);
        int[] typePalette = readPalette(in);
        finish(in, "palette");
        if (actorPalette.length != header.actorIds().length) {
            throw new CodecException("the actor palette does not match the header's actor list");
        }
        return new SharedColumns(ids, actorPalette, typePalette);
    }

    /**
     * Every row's id, by ordinal.
     *
     * @return the ids (the array itself: do not modify)
     */
    public long @NotNull [] ids() {
        return ids;
    }

    /**
     * The actor ids, most frequent first: a row's actor column indexes this.
     *
     * @return the palette (the array itself: do not modify)
     */
    public int @NotNull [] actorPalette() {
        return actorPalette;
    }

    /**
     * The subject and secondary type ids, most frequent first.
     *
     * @return the palette (the array itself: do not modify)
     */
    public int @NotNull [] typePalette() {
        return typePalette;
    }

    private static byte @NotNull [] frame(byte @NotNull [] bytes, int offset, int length) {
        return length == 0 ? new byte[0] : Compression.decompress(bytes, offset, length);
    }

    private static long @NotNull [] readSlots(@NotNull ByteReader in, @NotNull SegmentHeader header) {
        long base = header.baseSlot();
        long last = base + header.slotSpan();
        long[] slots = new long[header.events()];
        long slot = base;
        for (int i = 0; i < slots.length; i++) {
            long delta = in.readZigZag();
            if (delta > last - slot || delta < base - slot) {
                throw new CodecException("slot outside the segment's slot range");
            }
            slot += delta;
            slots[i] = slot;
        }
        finish(in, "time");
        return slots;
    }

    private static long @NotNull [] readIds(@NotNull SegmentHeader header, long @NotNull [] slots,
            int @NotNull [] nodeIndexes, byte @NotNull [] rank) {
        int[] nodes = header.nodeIds();
        long base = header.baseSlot();
        RankExpectation expectation = new RankExpectation(header.slotSpan(), nodes.length, slots.length);
        ByteReader in = rank.length > 0 ? new ByteReader(rank) : null;
        long[] ids = new long[slots.length];
        for (int i = 0; i < slots.length; i++) {
            long key = (slots[i] - base) * nodes.length + nodeIndexes[i];
            long value = expectation.expected(key);
            if (in != null) {
                value += in.readZigZag();
            }
            if (value < 0 || value > EventIds.MAX_RANK) {
                throw new CodecException("rank " + value + " out of range");
            }
            expectation.record(key, (int) value);
            ids[i] = EventIds.fromSlot(slots[i], nodes[nodeIndexes[i]], (int) value);
        }
        if (in != null) {
            finish(in, "rank");
        }
        return ids;
    }

    private static int @NotNull [] readPalette(@NotNull ByteReader in) {
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

    private static void finish(@NotNull ByteReader in, @NotNull String column) {
        if (in.hasRemaining()) {
            throw new CodecException(in.remaining() + " unread byte(s) in the " + column + " column");
        }
    }
}
