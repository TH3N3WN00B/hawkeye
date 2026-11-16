package gg.moonrise.hawkeye.util;

import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.NotNull;

/**
 * Packs a block position into one chunk-major 64-bit value:
 * {@code [chunk x: 22, signed][chunk z + 2^21: 22][x & 15: 4][z & 15: 4][y + 2048: 12]}.
 *
 * <p>Every block of a chunk column is one contiguous range of values, and so is a run of chunks that
 * share a chunk x, so an area is a handful of index range scans ({@link #columnRanges}). Signed
 * ordering is preserved: the chunk x sits in the sign bit's field as a two's complement number.
 */
public final class BlockPositions {

    /** The smallest packable block x or z: the world border fits with room to spare. */
    public static final int MIN_HORIZONTAL = -(1 << 25);

    /** The largest packable block x or z. */
    public static final int MAX_HORIZONTAL = (1 << 25) - 1;

    /** The smallest packable y. */
    public static final int MIN_Y = -2048;

    /** The largest packable y. */
    public static final int MAX_Y = 2047;

    /** Chunks per region side, as a shift: regions are 8 x 8 chunks. */
    public static final int REGION_SHIFT = 3;

    private static final int CHUNK_Z_OFFSET = 1 << 21;
    private static final long CHUNK_Z_MASK = (1L << 22) - 1;
    private static final long CHUNK_LOCAL_MASK = (1L << 20) - 1;

    private BlockPositions() {
    }

    /**
     * Packs a block position.
     *
     * @param x block x
     * @param y block y
     * @param z block z
     * @return the packed position
     * @throws IllegalArgumentException when the position is outside the packable range
     */
    public static long pack(int x, int y, int z) {
        if (x < MIN_HORIZONTAL || x > MAX_HORIZONTAL || z < MIN_HORIZONTAL || z > MAX_HORIZONTAL
                || y < MIN_Y || y > MAX_Y) {
            throw new IllegalArgumentException("position out of range: " + x + ", " + y + ", " + z);
        }
        return chunkBase(x >> 4, z >> 4) | ((long) (x & 15) << 16) | ((long) (z & 15) << 12) | (y - MIN_Y);
    }

    /**
     * The block x of a packed position.
     *
     * @param position a packed position
     * @return block x
     */
    public static int x(long position) {
        int chunkX = (int) (position >> 42);
        return (chunkX << 4) | (int) ((position >>> 16) & 15);
    }

    /**
     * The block y of a packed position.
     *
     * @param position a packed position
     * @return block y
     */
    public static int y(long position) {
        return (int) (position & 0xFFF) + MIN_Y;
    }

    /**
     * The block z of a packed position.
     *
     * @param position a packed position
     * @return block z
     */
    public static int z(long position) {
        int chunkZ = (int) ((position >>> 20) & CHUNK_Z_MASK) - CHUNK_Z_OFFSET;
        return (chunkZ << 4) | (int) ((position >>> 12) & 15);
    }

    /**
     * The chunk x of a packed position.
     *
     * @param position a packed position
     * @return chunk x
     */
    public static int chunkX(long position) {
        return (int) (position >> 42);
    }

    /**
     * The chunk z of a packed position.
     *
     * @param position a packed position
     * @return chunk z
     */
    public static int chunkZ(long position) {
        return (int) ((position >>> 20) & CHUNK_Z_MASK) - CHUNK_Z_OFFSET;
    }

    /**
     * The value ranges that cover every block of the chunks in a rectangle: one range per chunk x,
     * because the chunks of one chunk x are contiguous.
     *
     * @param minChunkX smallest chunk x
     * @param minChunkZ smallest chunk z
     * @param maxChunkX largest chunk x
     * @param maxChunkZ largest chunk z
     * @return the ranges, ordered by start
     */
    public static @NotNull List<Range> columnRanges(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
        if (minChunkX > maxChunkX || minChunkZ > maxChunkZ) {
            throw new IllegalArgumentException("empty chunk rectangle");
        }
        List<Range> ranges = new ArrayList<>(maxChunkX - minChunkX + 1);
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            ranges.add(new Range(chunkBase(chunkX, minChunkZ), chunkBase(chunkX, maxChunkZ) | CHUNK_LOCAL_MASK));
        }
        return ranges;
    }

    /**
     * The value ranges that cover a block rectangle (every y). The ranges are chunk-aligned, so they
     * may also cover blocks outside the rectangle; filter exact bounds after reading.
     *
     * @param minX smallest block x
     * @param minZ smallest block z
     * @param maxX largest block x
     * @param maxZ largest block z
     * @return the ranges, ordered by start
     */
    public static @NotNull List<Range> blockRanges(int minX, int minZ, int maxX, int maxZ) {
        return columnRanges(minX >> 4, minZ >> 4, maxX >> 4, maxZ >> 4);
    }

    /**
     * The region key of a packed position: regions are 8 x 8 chunks (128 x 128 blocks), keyed
     * region-x-major so a run of regions with the same region x is one contiguous key range.
     *
     * @param position a packed position
     * @return the region key
     */
    public static long regionKey(long position) {
        return regionKey(chunkX(position) >> REGION_SHIFT, chunkZ(position) >> REGION_SHIFT);
    }

    /**
     * The key of a region.
     *
     * @param regionX region x (chunk x &gt;&gt; 3)
     * @param regionZ region z (chunk z &gt;&gt; 3)
     * @return the key
     */
    public static long regionKey(int regionX, int regionZ) {
        return ((long) regionX << 32) | (((long) regionZ + (1L << 31)) & 0xFFFFFFFFL);
    }

    /**
     * The region x of a region key.
     *
     * @param key a region key
     * @return region x
     */
    public static int regionX(long key) {
        return (int) (key >> 32);
    }

    /**
     * The region z of a region key.
     *
     * @param key a region key
     * @return region z
     */
    public static int regionZ(long key) {
        return (int) ((key & 0xFFFFFFFFL) - (1L << 31));
    }

    /**
     * Packs a position from its region and its offset inside the region.
     *
     * @param regionKey the region key
     * @param localX    x inside the region, 0 to 127
     * @param y         block y
     * @param localZ    z inside the region, 0 to 127
     * @return the packed position
     */
    public static long fromRegion(long regionKey, int localX, int y, int localZ) {
        return pack((regionX(regionKey) << 7) | localX, y, (regionZ(regionKey) << 7) | localZ);
    }

    private static long chunkBase(int chunkX, int chunkZ) {
        return ((long) chunkX << 42) | (((long) chunkZ + CHUNK_Z_OFFSET) & CHUNK_Z_MASK) << 20;
    }

    /**
     * An inclusive range of packed positions.
     *
     * @param from the first value
     * @param to   the last value
     */
    public record Range(long from, long to) {
    }
}
