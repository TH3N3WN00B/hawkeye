package gg.moonrise.hawkeye.core.repository.segment;

import gg.moonrise.hawkeye.core.repository.EventRow;
import gg.moonrise.hawkeye.util.BlockPositions;
import gg.moonrise.hawkeye.util.codec.ByteWriter;
import org.jetbrains.annotations.NotNull;

/**
 * The raw columns of one unit while it is written. The scalar frame is the eleven columns in this
 * order, each as {@code varint byte length + bytes}: world index, chunk x delta (zig-zag), chunk z
 * delta (zig-zag), local ({@code (x & 15) << 4 | (z & 15)}, one byte), y delta (zig-zag), actor palette
 * index, action code, subject (0 or type palette index + 1), secondary (likewise), amount and data
 * length (0 for none, else length + 1). Deltas start from 0 in every unit. The payload frame is the
 * data bytes of the unit's rows, concatenated in row order.
 */
public final class UnitColumns {

    /** How many scalar columns a unit has. */
    public static final int COLUMNS = 11;

    private final ByteWriter[] columns = new ByteWriter[COLUMNS];
    private final ByteWriter payload;
    private int chunkX;
    private int chunkZ;
    private int y;

    /**
     * Empty columns.
     *
     * @param rows how many rows will be added (sizes the buffers)
     */
    public UnitColumns(int rows) {
        for (int i = 0; i < COLUMNS; i++) {
            columns[i] = new ByteWriter(rows + 16);
        }
        payload = new ByteWriter(256);
    }

    /**
     * Adds a row.
     *
     * @param world     the row's header world index
     * @param row       the row
     * @param actor     its actor palette index
     * @param subject   its subject column value
     * @param secondary its secondary column value
     */
    public void add(int world, @NotNull EventRow row, int actor, int subject, int secondary) {
        long position = row.position();
        int nextChunkX = BlockPositions.chunkX(position);
        int nextChunkZ = BlockPositions.chunkZ(position);
        int nextY = BlockPositions.y(position);
        columns[0].writeVarLong(world);
        columns[1].writeZigZag((long) nextChunkX - chunkX);
        columns[2].writeZigZag((long) nextChunkZ - chunkZ);
        columns[3].writeByte((BlockPositions.x(position) & 15) << 4 | (BlockPositions.z(position) & 15));
        columns[4].writeZigZag((long) nextY - y);
        columns[5].writeVarLong(actor);
        columns[6].writeVarLong(row.action());
        columns[7].writeVarLong(subject);
        columns[8].writeVarLong(secondary);
        columns[9].writeVarLong(row.amount());
        byte[] data = row.data();
        columns[10].writeVarLong(data == null ? 0 : data.length + 1L);
        if (data != null) {
            payload.writeRaw(data);
        }
        chunkX = nextChunkX;
        chunkZ = nextChunkZ;
        y = nextY;
    }

    /**
     * The raw scalar frame.
     *
     * @return the columns, each length-prefixed
     */
    public byte @NotNull [] scalars() {
        ByteWriter out = new ByteWriter(1024);
        for (ByteWriter column : columns) {
            out.writeBytes(column.toByteArray());
        }
        return out.toByteArray();
    }

    /**
     * The raw payload frame.
     *
     * @return the data bytes, empty when no row has data
     */
    public byte @NotNull [] payload() {
        return payload.toByteArray();
    }
}
