package gg.moonrise.hawkeye.core.repository.place;

import gg.moonrise.hawkeye.core.repository.segment.SegmentHeader;

/**
 * The keys of the place index. A chunk key is a packed position without its 20 in-chunk bits
 * ({@code position >> 20}: chunk x above chunk z + 2^21), and a region key drops 5 more bits of each
 * chunk coordinate (32 x 32 chunks, the regions of the segment header): chunk x &gt;&gt; 5 above
 * (chunk z + 2^21) &gt;&gt; 5. Both order like packed positions, so the chunks of one chunk x, and the
 * regions of one region x, are contiguous key ranges. Inside a region a chunk is numbered
 * {@code (x & 31) << 5 | (z & 31)}, which orders like its key.
 */
public final class PlaceKeys {

    /** The in-chunk bits of a packed position. */
    public static final int CHUNK_SHIFT = 20;

    /** The in-chunk part of a packed position. */
    public static final long LOCAL_MASK = (1L << CHUNK_SHIFT) - 1;

    /** Chunk bits per region side. */
    public static final int REGION_SHIFT = SegmentHeader.REGION_SHIFT;

    /** Chunks per region. */
    public static final int REGION_CHUNKS = 1 << (2 * REGION_SHIFT);

    private static final int CHUNK_Z_BITS = 22;
    private static final long CHUNK_Z_MASK = (1L << CHUNK_Z_BITS) - 1;
    private static final int REGION_Z_BITS = CHUNK_Z_BITS - REGION_SHIFT;
    private static final long REGION_Z_MASK = (1L << REGION_Z_BITS) - 1;
    private static final int SIDE_MASK = (1 << REGION_SHIFT) - 1;

    private PlaceKeys() {
    }

    /**
     * The chunk key of a packed position.
     *
     * @param position the packed position
     * @return the key
     */
    public static long chunk(long position) {
        return position >> CHUNK_SHIFT;
    }

    /**
     * The in-chunk part of a packed position (local x, local z, y), which orders like the position.
     *
     * @param position the packed position
     * @return 0 to 2^20 - 1
     */
    public static int local(long position) {
        return (int) (position & LOCAL_MASK);
    }

    /**
     * The packed position of a chunk's first block.
     *
     * @param chunk the chunk key
     * @return the position
     */
    public static long base(long chunk) {
        return chunk << CHUNK_SHIFT;
    }

    /**
     * The region key of a chunk.
     *
     * @param chunk the chunk key
     * @return the region key
     */
    public static long region(long chunk) {
        long chunkX = chunk >> CHUNK_Z_BITS;
        long chunkZ = chunk & CHUNK_Z_MASK;
        return (chunkX >> REGION_SHIFT) << REGION_Z_BITS | chunkZ >> REGION_SHIFT;
    }

    /**
     * The number of a chunk inside its region.
     *
     * @param chunk the chunk key
     * @return 0 to {@link #REGION_CHUNKS} - 1
     */
    public static int indexInRegion(long chunk) {
        long chunkX = chunk >> CHUNK_Z_BITS;
        long chunkZ = chunk & CHUNK_Z_MASK;
        return (int) ((chunkX & SIDE_MASK) << REGION_SHIFT | chunkZ & SIDE_MASK);
    }

    /**
     * The chunk key of a chunk of a region.
     *
     * @param region the region key
     * @param index  the chunk's number inside it
     * @return the chunk key
     */
    public static long chunkOf(long region, int index) {
        long regionX = region >> REGION_Z_BITS;
        long regionZ = region & REGION_Z_MASK;
        long chunkX = regionX << REGION_SHIFT | index >> REGION_SHIFT;
        long chunkZ = regionZ << REGION_SHIFT | index & SIDE_MASK;
        return chunkX << CHUNK_Z_BITS | chunkZ;
    }

    /**
     * The region key of a region given by its coordinates (chunk coordinates &gt;&gt; 5), as the segment
     * header lists the regions of unplaced rows.
     *
     * @param regionX region x
     * @param regionZ region z
     * @return the region key
     */
    public static long regionOf(int regionX, int regionZ) {
        long chunkZ = ((long) regionZ << REGION_SHIFT) + (1L << (CHUNK_Z_BITS - 1));
        return (long) regionX << REGION_Z_BITS | chunkZ >> REGION_SHIFT;
    }
}
