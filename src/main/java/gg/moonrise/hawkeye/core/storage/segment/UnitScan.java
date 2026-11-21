package gg.moonrise.hawkeye.core.storage.segment;

import gg.moonrise.hawkeye.core.repository.event.EventRow;
import gg.moonrise.hawkeye.util.position.BlockPositions;
import gg.moonrise.hawkeye.util.codec.binary.ByteReader;
import gg.moonrise.hawkeye.util.codec.binary.CodecException;
import gg.moonrise.hawkeye.util.codec.binary.Compression;
import java.util.Arrays;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One unit's frames, decoded column by column on demand ({@link UnitColumns} describes the layout).
 * The scalar frame is decompressed when the scan is made; each column is decoded into a primitive
 * array the first time it is asked for, and the payload frame only when a row's data is. Every column
 * decoded is checked like a full decode checks it. Not thread-safe.
 */
public final class UnitScan {

    private static final int MIN_CHUNK = -(1 << 21);
    private static final int MAX_CHUNK = (1 << 21) - 1;

    private final SegmentHeader header;
    private final int unit;
    private final int rows;
    private final byte[] bytes;
    private final int payloadOffset;
    private final int payloadLength;
    private final byte[] scalars;
    private final int[] columnOffsets = new int[UnitColumns.COLUMNS];
    private final int[] columnLengths = new int[UnitColumns.COLUMNS];
    private int[] worldIndexes;
    private long[] positions;
    private int positionsDecoded;
    private ByteReader[] positionReaders;
    private long chunkX;
    private long chunkZ;
    private long y;
    private int[] actorIndexes;
    private int[] actions;
    private int[] subjects;
    private int[] secondaries;
    private int[] amounts;
    private int[] dataStarts;
    private boolean[] hasData;
    private byte[] payload;

    /**
     * Decompresses a unit's scalar frame and finds its columns.
     *
     * @param header the segment header
     * @param unit   the unit number
     * @param bytes  an array holding the unit's frames
     * @param offset where they start in it
     * @param length their length; must equal {@code header.unit(unit).length()}
     * @throws CodecException when the frames are corrupt
     */
    public UnitScan(@NotNull SegmentHeader header, int unit, byte @NotNull [] bytes, int offset, int length) {
        SegmentUnit info = header.unitInfo(unit);
        if (length != info.length() || offset < 0 || offset > bytes.length - length) {
            throw new CodecException("unit " + unit + " frames are " + length + " bytes, the header says "
                    + info.length());
        }
        this.header = header;
        this.unit = unit;
        this.rows = info.rows();
        this.bytes = bytes;
        this.payloadOffset = offset + info.scalarLength();
        this.payloadLength = info.payloadLength();
        this.scalars = Compression.decompress(bytes, offset, info.scalarLength());
        ByteReader frame = new ByteReader(scalars);
        for (int i = 0; i < UnitColumns.COLUMNS; i++) {
            int columnLength = frame.readVarInt();
            columnOffsets[i] = scalars.length - frame.remaining();
            columnLengths[i] = Math.min(columnLength, frame.remaining());
            frame.skip(columnLength);
        }
        if (frame.hasRemaining()) {
            throw new CodecException("unread bytes after unit " + unit + "'s columns");
        }
    }

    /**
     * The unit number.
     *
     * @return the unit
     */
    public int unit() {
        return unit;
    }

    /**
     * How many rows the unit holds.
     *
     * @return the row count
     */
    public int rows() {
        return rows;
    }

    /**
     * The ordinal of the unit's first row.
     *
     * @return the ordinal
     */
    public int start() {
        return header.unitStart(unit);
    }

    /**
     * Per row: the header world index.
     *
     * @return the indexes (the array itself: do not modify)
     */
    public int @NotNull [] worldIndexes() {
        if (worldIndexes == null) {
            worldIndexes = ints(0, header.worldIds().length, "world");
        }
        return worldIndexes;
    }

    /**
     * Per row: the packed position.
     *
     * @return the positions (the array itself: do not modify)
     */
    public long @NotNull [] positions() {
        decodePositions(rows, Integer.MAX_VALUE, Long.MAX_VALUE);
        return positions;
    }

    /**
     * The positions decoded so far ({@link #positionsUpTo}), without decoding more.
     *
     * @return the array (the array itself: do not modify); rows not decoded yet hold 0
     */
    public long @NotNull [] decodedPositions() {
        if (positions == null) {
            decodePositions(0, Integer.MAX_VALUE, Long.MAX_VALUE);
        }
        return positions;
    }

    /**
     * Decodes the positions of a placed unit only up to a key: its rows are sorted by (world index,
     * position), so no row after the first one beyond the key can be inside an area that ends there.
     *
     * @param world    the header world index of the key
     * @param position the packed position of the key
     * @return how many leading rows have a key not beyond it; their positions are in {@link #positions}'s
     *         array, the rest are decoded when {@link #positions} is called
     * @throws IllegalStateException when the unit is not placed
     */
    public int positionsUpTo(int world, long position) {
        if (!header.unitInfo(unit).placed()) {
            throw new IllegalStateException("unit " + unit + " is not ordered by place");
        }
        return decodePositions(rows, world, position);
    }

    /** Decodes positions until {@code limit} rows are done or a row's key passes (world, position). */
    private int decodePositions(int limit, int world, long position) {
        if (positions == null) {
            positions = new long[rows];
            positionReaders = new ByteReader[] {column(1), column(2), column(3), column(4)};
            if (positionReaders[2].remaining() != rows) {
                throw new CodecException("unit " + unit + " local column does not hold " + rows + " rows");
            }
        }
        int[] worlds = world == Integer.MAX_VALUE ? null : worldIndexes();
        for (int i = 0; i < positionsDecoded; i++) {
            if (worlds != null && beyond(worlds[i], positions[i], world, position)) {
                return i;
            }
        }
        while (positionsDecoded < limit) {
            int i = positionsDecoded;
            chunkX += positionReaders[0].readZigZag();
            chunkZ += positionReaders[1].readZigZag();
            int local = positionReaders[2].readByte();
            y += positionReaders[3].readZigZag();
            if (chunkX < MIN_CHUNK || chunkX > MAX_CHUNK || chunkZ < MIN_CHUNK || chunkZ > MAX_CHUNK
                    || y < BlockPositions.MIN_Y || y > BlockPositions.MAX_Y) {
                throw new CodecException("position out of range in row " + (start() + i));
            }
            positions[i] = BlockPositions.pack((int) chunkX << 4 | local >>> 4, (int) y,
                    (int) chunkZ << 4 | local & 15);
            positionsDecoded++;
            if (worlds != null && beyond(worlds[i], positions[i], world, position)) {
                return i;
            }
        }
        if (positionsDecoded == rows) {
            for (ByteReader reader : positionReaders) {
                finish(reader);
            }
        }
        return positionsDecoded;
    }

    private static boolean beyond(int rowWorld, long rowPosition, int world, long position) {
        return rowWorld > world || rowWorld == world && rowPosition > position;
    }

    /**
     * Per row: the actor palette index (checked against the palette by the caller).
     *
     * @return the indexes (the array itself: do not modify)
     */
    public int @NotNull [] actorIndexes() {
        if (actorIndexes == null) {
            actorIndexes = ints(5, header.actorIds().length, "actor");
        }
        return actorIndexes;
    }

    /**
     * Per row: the action code.
     *
     * @return the codes (the array itself: do not modify)
     */
    public int @NotNull [] actions() {
        if (actions == null) {
            actions = ints(6, Integer.MAX_VALUE, "action");
        }
        return actions;
    }

    /**
     * Per row: the subject column (0, or type palette index + 1).
     *
     * @return the values (the array itself: do not modify)
     */
    public int @NotNull [] subjects() {
        if (subjects == null) {
            subjects = ints(7, Integer.MAX_VALUE, "subject");
        }
        return subjects;
    }

    /**
     * Builds one row, decoding whatever columns it still needs.
     *
     * @param row      the row in the unit
     * @param id       its id
     * @param flags    its flags
     * @param palettes the segment's palettes
     * @return the row
     * @throws CodecException when a column is corrupt
     */
    public @NotNull EventRow row(int row, long id, int flags, @NotNull Palettes palettes) {
        int world = header.worldIds()[worldIndexes()[row]];
        long position = positions()[row];
        int actor = palettes.actors()[index(actorIndexes()[row], palettes.actors().length, "actor")];
        int subject = type(subjects()[row], palettes);
        if (secondaries == null) {
            secondaries = ints(8, Integer.MAX_VALUE, "secondary");
            amounts = ints(9, Integer.MAX_VALUE, "amount");
        }
        int secondary = type(secondaries[row], palettes);
        return new EventRow(id, world, position, actor, actions()[row], subject, secondary, amounts[row], flags,
                data(row));
    }

    private byte @Nullable [] data(int row) {
        if (dataStarts == null) {
            readDataStarts();
        }
        if (!hasData[row]) {
            return null;
        }
        return Arrays.copyOfRange(payload(), dataStarts[row], dataStarts[row + 1]);
    }

    private byte @NotNull [] payload() {
        if (payload == null) {
            payload = payloadLength == 0 ? new byte[0] : Compression.decompress(bytes, payloadOffset, payloadLength);
            if (payload.length != dataStarts[rows]) {
                throw new CodecException("the payload frame of unit " + unit + " holds " + payload.length
                        + " bytes, its rows " + dataStarts[rows]);
            }
        }
        return payload;
    }

    /** Per row where its data starts in the payload, then the total; and which rows have data. */
    private void readDataStarts() {
        ByteReader in = column(10);
        int[] starts = new int[rows + 1];
        boolean[] present = new boolean[rows];
        long at = 0;
        for (int i = 0; i < rows; i++) {
            long length = in.readVarLong();
            if (length < 0 || length - 1 > Integer.MAX_VALUE - at) {
                throw new CodecException("data length out of range in unit " + unit);
            }
            starts[i] = (int) at;
            present[i] = length != 0;
            at += length == 0 ? 0 : length - 1;
        }
        finish(in);
        starts[rows] = (int) at;
        hasData = present;
        dataStarts = starts;
    }

    private int @NotNull [] ints(int column, int bound, @NotNull String what) {
        ByteReader in = column(column);
        int[] out = new int[rows];
        for (int i = 0; i < rows; i++) {
            long value = in.readVarLong();
            if (value < 0 || value >= bound) {
                throw new CodecException(what + " value " + value + " out of range in unit " + unit);
            }
            out[i] = (int) value;
        }
        finish(in);
        return out;
    }

    private @NotNull ByteReader column(int column) {
        return new ByteReader(scalars, columnOffsets[column], columnLengths[column]);
    }

    private void finish(@NotNull ByteReader in) {
        if (in.hasRemaining()) {
            throw new CodecException("unread bytes in a column of unit " + unit);
        }
    }

    private static int type(int value, @NotNull Palettes palettes) {
        return value == 0 ? 0 : palettes.types()[index(value - 1, palettes.types().length, "type")];
    }

    private static int index(long value, int size, @NotNull String what) {
        if (value < 0 || value >= size) {
            throw new CodecException(what + " index " + value + " out of " + size);
        }
        return (int) value;
    }

    /**
     * Checks every column and the payload frame, as a full decode must.
     *
     * @throws CodecException when something is corrupt
     */
    public void checkAll() {
        worldIndexes();
        positions();
        actorIndexes();
        actions();
        subjects();
        if (secondaries == null) {
            secondaries = ints(8, Integer.MAX_VALUE, "secondary");
            amounts = ints(9, Integer.MAX_VALUE, "amount");
        }
        if (dataStarts == null) {
            readDataStarts();
        }
        payload();
    }
}
