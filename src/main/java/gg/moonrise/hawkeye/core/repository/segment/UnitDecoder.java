package gg.moonrise.hawkeye.core.repository.segment;

import gg.moonrise.hawkeye.core.repository.EventRow;
import gg.moonrise.hawkeye.util.BlockPositions;
import gg.moonrise.hawkeye.util.codec.ByteReader;
import gg.moonrise.hawkeye.util.codec.CodecException;
import gg.moonrise.hawkeye.util.codec.Compression;
import java.util.Arrays;
import java.util.List;
import org.jetbrains.annotations.NotNull;

/** Decodes one unit's frames ({@link UnitColumns} describes the layout). */
public final class UnitDecoder {

    private static final int MIN_CHUNK = -(1 << 21);
    private static final int MAX_CHUNK = (1 << 21) - 1;

    private UnitDecoder() {
    }

    /**
     * Decodes a unit and appends its rows, in stored order.
     *
     * @param header     the segment header
     * @param shared     the decoded shared frames
     * @param unit       the unit number
     * @param bytes      an array holding the unit's frames
     * @param offset     where they start in it
     * @param length     their length; must equal {@code header.unit(unit).length()}
     * @param rolledBack ascending ordinals to flag {@link EventRow#ROLLED_BACK}
     * @param out        where the rows go
     * @throws CodecException when the frames are corrupt
     */
    public static void decode(@NotNull SegmentHeader header, @NotNull SharedColumns shared, int unit,
            byte @NotNull [] bytes, int offset, int length, int @NotNull [] rolledBack, @NotNull List<EventRow> out) {
        SegmentUnit info = header.unitInfo(unit);
        if (length != info.length() || offset < 0 || offset > bytes.length - length) {
            throw new CodecException("unit " + unit + " frames are " + length + " bytes, the header says "
                    + info.length());
        }
        byte[] scalars = Compression.decompress(bytes, offset, info.scalarLength());
        byte[] payload = info.payloadLength() == 0 ? new byte[0]
                : Compression.decompress(bytes, offset + info.scalarLength(), info.payloadLength());
        ByteReader frame = new ByteReader(scalars);
        ByteReader[] columns = new ByteReader[UnitColumns.COLUMNS];
        for (int i = 0; i < columns.length; i++) {
            int columnLength = frame.readVarInt();
            columns[i] = new ByteReader(scalars, scalars.length - frame.remaining(), Math.min(columnLength,
                    frame.remaining()));
            frame.skip(columnLength);
        }
        if (frame.hasRemaining()) {
            throw new CodecException("unread bytes after unit " + unit + "'s columns");
        }
        int rows = info.rows();
        if (columns[3].remaining() != rows) {
            throw new CodecException("unit " + unit + " local column does not hold " + rows + " rows");
        }
        new RowReader(header, shared, columns, payload).read(header.unitStart(unit), rows, rolledBack, out);
        for (ByteReader column : columns) {
            if (column.hasRemaining()) {
                throw new CodecException("unread bytes in a column of unit " + unit);
            }
        }
    }

    /** Walks a unit's columns row by row. */
    private static final class RowReader {

        private final int[] worlds;
        private final long[] ids;
        private final int[] actorPalette;
        private final int[] typePalette;
        private final ByteReader[] columns;
        private final byte[] payload;
        private int payloadAt;

        private RowReader(@NotNull SegmentHeader header, @NotNull SharedColumns shared,
                @NotNull ByteReader @NotNull [] columns, byte @NotNull [] payload) {
            this.worlds = header.worldIds();
            this.ids = shared.ids();
            this.actorPalette = shared.actorPalette();
            this.typePalette = shared.typePalette();
            this.columns = columns;
            this.payload = payload;
        }

        private void read(int start, int rows, int @NotNull [] rolledBack, @NotNull List<EventRow> out) {
            int flagAt = Arrays.binarySearch(rolledBack, start);
            if (flagAt < 0) {
                flagAt = -flagAt - 1;
            }
            long chunkX = 0;
            long chunkZ = 0;
            long y = 0;
            for (int i = 0; i < rows; i++) {
                int ordinal = start + i;
                int world = worlds[index(columns[0].readVarLong(), worlds.length, "world")];
                chunkX += columns[1].readZigZag();
                chunkZ += columns[2].readZigZag();
                int local = columns[3].readByte();
                y += columns[4].readZigZag();
                if (chunkX < MIN_CHUNK || chunkX > MAX_CHUNK || chunkZ < MIN_CHUNK || chunkZ > MAX_CHUNK
                        || y < BlockPositions.MIN_Y || y > BlockPositions.MAX_Y) {
                    throw new CodecException("position out of range in row " + ordinal);
                }
                long position = BlockPositions.pack((int) chunkX << 4 | local >>> 4, (int) y,
                        (int) chunkZ << 4 | local & 15);
                int actor = actorPalette[index(columns[5].readVarLong(), actorPalette.length, "actor")];
                int action = columns[6].readVarInt();
                int subject = type(columns[7].readVarLong());
                int secondary = type(columns[8].readVarLong());
                int amount = columns[9].readVarInt();
                long dataLength = columns[10].readVarLong();
                byte[] data = dataLength == 0 ? null : data(dataLength - 1);
                int flags = 0;
                if (flagAt < rolledBack.length && rolledBack[flagAt] == ordinal) {
                    flags = EventRow.ROLLED_BACK;
                    flagAt++;
                }
                out.add(new EventRow(ids[ordinal], world, position, actor, action, subject, secondary, amount, flags,
                        data));
            }
            if (payloadAt != payload.length) {
                throw new CodecException("the payload frame holds " + (payload.length - payloadAt) + " extra bytes");
            }
        }

        private int type(long value) {
            return value == 0 ? 0 : typePalette[index(value - 1, typePalette.length, "type")];
        }

        private byte @NotNull [] data(long length) {
            if (length > payload.length - payloadAt) {
                throw new CodecException("data length " + length + " exceeds the payload frame");
            }
            byte[] data = Arrays.copyOfRange(payload, payloadAt, payloadAt + (int) length);
            payloadAt += (int) length;
            return data;
        }

        private static int index(long value, int size, @NotNull String what) {
            if (value < 0 || value >= size) {
                throw new CodecException(what + " index " + value + " out of " + size);
            }
            return (int) value;
        }
    }
}
