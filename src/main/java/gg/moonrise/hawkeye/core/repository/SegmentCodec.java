package gg.moonrise.hawkeye.core.repository;

import gg.moonrise.hawkeye.util.BlockPositions;
import gg.moonrise.hawkeye.util.EventIds;
import gg.moonrise.hawkeye.util.codec.ByteReader;
import gg.moonrise.hawkeye.util.codec.ByteWriter;
import gg.moonrise.hawkeye.util.codec.CodecException;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import org.jetbrains.annotations.NotNull;

/**
 * The archive format: the events of one world region (8 x 8 chunks), sorted by id, stored column by
 * column and deflated. Each column holds similar small numbers next to each other (time deltas,
 * positions inside the region, indexes into per-segment palettes of actors and types), which is what
 * makes an archived event a few bytes instead of a row with two index entries.
 * <pre>
 *   byte version (1), then deflated:
 *   count, nodes[], actor palette[], type palette[],
 *   time deltas (zig-zag), sequences, node indexes (only with several nodes), x, z and y deltas,
 *   actor indexes, actions, subject indexes, secondary indexes, amounts, flags, data lengths, data
 * </pre>
 * The format is a data contract: a new layout is a new version byte, and old versions stay readable.
 */
public final class SegmentCodec {

    /** Decoding refuses segments that inflate beyond this. */
    public static final int MAX_INFLATED_BYTES = 64 * 1024 * 1024;

    private static final int VERSION = 1;
    private static final int TIME_SHIFT = EventIds.NODE_BITS + EventIds.SEQUENCE_BITS;

    private SegmentCodec() {
    }

    /**
     * Encodes the events of one region.
     *
     * @param rows   the events, all of one world and region, sorted by id
     * @param region the region key ({@code BlockPositions.regionKey})
     * @return the segment body
     */
    public static byte @NotNull [] encode(@NotNull List<EventRow> rows, long region) {
        Palette nodes = new Palette();
        Palette actors = new Palette();
        Palette types = new Palette();
        for (EventRow row : rows) {
            if (BlockPositions.regionKey(row.position()) != region) {
                throw new IllegalArgumentException("event " + row.id() + " is outside region " + region);
            }
            nodes.index(EventIds.node(row.id()));
            actors.index(row.actor());
            if (row.subject() != 0) {
                types.index(row.subject());
            }
            if (row.secondary() != 0) {
                types.index(row.secondary());
            }
        }
        ByteWriter out = new ByteWriter(rows.size() * 12 + 64);
        out.writeVarLong(rows.size());
        nodes.write(out);
        actors.write(out);
        types.write(out);
        long previousTime = 0;
        long previousId = -1;
        for (EventRow row : rows) {
            if (row.id() <= previousId) {
                throw new IllegalArgumentException("events must be sorted by id");
            }
            previousId = row.id();
            long time = row.id() >>> TIME_SHIFT;
            out.writeZigZag(time - previousTime);
            previousTime = time;
        }
        rows.forEach(row -> out.writeVarLong(row.id() & EventIds.MAX_SEQUENCE));
        if (nodes.size() > 1) {
            rows.forEach(row -> out.writeVarLong(nodes.index(EventIds.node(row.id()))));
        }
        writePositions(out, rows);
        rows.forEach(row -> out.writeVarLong(actors.index(row.actor())));
        rows.forEach(row -> out.writeVarLong(row.action()));
        rows.forEach(row -> out.writeVarLong(row.subject() == 0 ? 0 : types.index(row.subject()) + 1L));
        rows.forEach(row -> out.writeVarLong(row.secondary() == 0 ? 0 : types.index(row.secondary()) + 1L));
        rows.forEach(row -> out.writeVarLong(Math.max(0, row.amount())));
        rows.forEach(row -> out.writeVarLong(row.flags()));
        rows.forEach(row -> out.writeVarLong(row.data() == null ? 0 : row.data().length + 1L));
        rows.forEach(row -> {
            if (row.data() != null) {
                out.writeRaw(row.data());
            }
        });
        byte[] deflated = deflate(out.toByteArray());
        byte[] body = new byte[deflated.length + 1];
        body[0] = VERSION;
        System.arraycopy(deflated, 0, body, 1, deflated.length);
        return body;
    }

    /**
     * Decodes a segment body.
     *
     * @param body   the body
     * @param world  the segment's world id
     * @param region the segment's region key
     * @return the events, sorted by id
     * @throws CodecException when the body is corrupt
     */
    public static @NotNull List<EventRow> decode(byte @NotNull [] body, int world, long region) {
        if (body.length == 0 || body[0] != VERSION) {
            throw new CodecException("unknown segment version " + (body.length == 0 ? "none" : body[0]));
        }
        byte[] deflated = new byte[body.length - 1];
        System.arraycopy(body, 1, deflated, 0, deflated.length);
        ByteReader in = new ByteReader(inflate(deflated));
        int count = in.readVarInt();
        if (count > MAX_INFLATED_BYTES) {
            throw new CodecException("implausible segment size " + count);
        }
        long[] nodes = readPalette(in);
        long[] actors = readPalette(in);
        long[] types = readPalette(in);
        long[] times = new long[count];
        long time = 0;
        for (int i = 0; i < count; i++) {
            time += in.readZigZag();
            times[i] = time;
        }
        long[] sequences = column(in, count);
        long[] nodeIndexes = nodes.length > 1 ? column(in, count) : new long[count];
        long[] positions = readPositions(in, count, region);
        long[] actorIndexes = column(in, count);
        long[] actions = column(in, count);
        long[] subjects = column(in, count);
        long[] secondaries = column(in, count);
        long[] amounts = column(in, count);
        long[] flags = column(in, count);
        long[] lengths = column(in, count);
        List<EventRow> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            byte[] data = lengths[i] == 0 ? null : in.readRaw((int) lengths[i] - 1);
            long node = pick(nodes, nodeIndexes[i]);
            long id = (times[i] << TIME_SHIFT) | (node << EventIds.SEQUENCE_BITS) | sequences[i];
            rows.add(new EventRow(id, world, positions[i], (int) pick(actors, actorIndexes[i]),
                    (int) actions[i], subjects[i] == 0 ? 0 : (int) pick(types, subjects[i] - 1),
                    secondaries[i] == 0 ? 0 : (int) pick(types, secondaries[i] - 1), (int) amounts[i],
                    (int) flags[i], data));
        }
        return rows;
    }

    /** Positions as three zig-zag delta columns (x, z, y inside the region): neighbours are usually close. */
    private static void writePositions(@NotNull ByteWriter out, @NotNull List<EventRow> rows) {
        int[] previous = new int[3];
        for (int axis = 0; axis < 3; axis++) {
            previous[axis] = 0;
            for (EventRow row : rows) {
                int value = axis(row.position(), axis);
                out.writeZigZag(value - previous[axis]);
                previous[axis] = value;
            }
        }
    }

    private static long @NotNull [] readPositions(@NotNull ByteReader in, int count, long region) {
        int[][] axes = new int[3][count];
        for (int axis = 0; axis < 3; axis++) {
            int value = 0;
            for (int i = 0; i < count; i++) {
                value += (int) in.readZigZag();
                axes[axis][i] = value;
            }
        }
        long[] positions = new long[count];
        for (int i = 0; i < count; i++) {
            int x = axes[0][i];
            int z = axes[1][i];
            int y = axes[2][i];
            if (x < 0 || x > 127 || z < 0 || z > 127 || y < BlockPositions.MIN_Y || y > BlockPositions.MAX_Y) {
                throw new CodecException("position outside its region");
            }
            positions[i] = BlockPositions.fromRegion(region, x, y, z);
        }
        return positions;
    }

    private static int axis(long position, int axis) {
        return switch (axis) {
            case 0 -> BlockPositions.x(position) & 127;
            case 1 -> BlockPositions.z(position) & 127;
            default -> BlockPositions.y(position);
        };
    }

    private static long @NotNull [] column(@NotNull ByteReader in, int count) {
        long[] values = new long[count];
        for (int i = 0; i < count; i++) {
            values[i] = in.readVarLong();
        }
        return values;
    }

    private static long @NotNull [] readPalette(@NotNull ByteReader in) {
        int size = in.readVarInt();
        if (size > MAX_INFLATED_BYTES) {
            throw new CodecException("implausible palette size " + size);
        }
        return column(in, size);
    }

    private static long pick(long @NotNull [] palette, long index) {
        if (index < 0 || index >= palette.length) {
            throw new CodecException("palette index " + index + " out of " + palette.length);
        }
        return palette[(int) index];
    }

    private static byte @NotNull [] deflate(byte @NotNull [] input) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, true);
        try {
            deflater.setInput(input);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(input.length / 3 + 64);
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                out.write(buffer, 0, deflater.deflate(buffer));
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private static byte @NotNull [] inflate(byte @NotNull [] input) {
        Inflater inflater = new Inflater(true);
        try {
            inflater.setInput(input);
            ByteArrayOutputStream out = new ByteArrayOutputStream(input.length * 4 + 64);
            byte[] buffer = new byte[8192];
            while (!inflater.finished()) {
                int read = inflater.inflate(buffer);
                if (read == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    throw new CodecException("truncated segment");
                }
                out.write(buffer, 0, read);
                if (out.size() > MAX_INFLATED_BYTES) {
                    throw new CodecException("segment inflates beyond " + MAX_INFLATED_BYTES + " bytes");
                }
            }
            return out.toByteArray();
        } catch (DataFormatException e) {
            throw new CodecException("corrupt segment", e);
        } finally {
            inflater.end();
        }
    }

    /** Distinct values in first-seen order, each with its index. */
    private static final class Palette {

        private final Map<Long, Integer> indexes = new HashMap<>();
        private final List<Long> values = new ArrayList<>();

        private int index(long value) {
            Integer index = indexes.get(value);
            if (index != null) {
                return index;
            }
            indexes.put(value, values.size());
            values.add(value);
            return values.size() - 1;
        }

        private int size() {
            return values.size();
        }

        private void write(@NotNull ByteWriter out) {
            out.writeVarLong(values.size());
            values.forEach(out::writeVarLong);
        }
    }
}
