package gg.moonrise.hawkeye.core.storage.segment;

import gg.moonrise.hawkeye.util.position.BlockPositions;
import java.util.Arrays;
import org.jetbrains.annotations.NotNull;

/**
 * The regions (32 x 32 chunks, {@code chunk >> 5}) that hold a segment's unplaced rows, as parallel
 * arrays sorted by (world index, region x, region z), without repeats. The header stores them
 * ({@link SegmentHeader#unplacedRegions()}); a world or area filter that reaches none of them skips
 * the unplaced units.
 *
 * @param worlds per region: the header world index
 * @param xs     per region: region x
 * @param zs     per region: region z
 */
public record Regions(int @NotNull [] worlds, int @NotNull [] xs, int @NotNull [] zs) {

    /**
     * Checks that the arrays are parallel.
     *
     * @param worlds per region: the header world index
     * @param xs     per region: region x
     * @param zs     per region: region z
     */
    public Regions {
        if (xs.length != worlds.length || zs.length != worlds.length) {
            throw new IllegalArgumentException("region arrays of different lengths");
        }
    }

    /**
     * The distinct regions of some places.
     *
     * @param worldIndexes per place: its header world index
     * @param positions    per place: its packed position
     * @param from         the first place to take
     * @param to           the place after the last
     * @return the regions, sorted
     */
    public static @NotNull Regions of(int @NotNull [] worldIndexes, long @NotNull [] positions, int from, int to) {
        long[] keys = new long[to - from];
        for (int i = from; i < to; i++) {
            long position = positions[i];
            keys[i - from] = key(worldIndexes[i], region(BlockPositions.chunkX(position)),
                    region(BlockPositions.chunkZ(position)));
        }
        long[] sorted = Arrays.stream(keys).sorted().distinct().toArray();
        int[] worlds = new int[sorted.length];
        int[] xs = new int[sorted.length];
        int[] zs = new int[sorted.length];
        for (int i = 0; i < sorted.length; i++) {
            worlds[i] = (int) (sorted[i] >>> 40);
            xs[i] = (int) ((sorted[i] >>> 20) & 0xFFFFF) - (1 << 19);
            zs[i] = (int) (sorted[i] & 0xFFFFF) - (1 << 19);
        }
        return new Regions(worlds, xs, zs);
    }

    /**
     * The region coordinate of a chunk coordinate.
     *
     * @param chunk a chunk x or z
     * @return {@code chunk >> 5}
     */
    public static int region(int chunk) {
        return chunk >> SegmentHeader.REGION_SHIFT;
    }

    /**
     * Compares two regions by (world index, x, z).
     *
     * @param a      one list
     * @param i      a region of it
     * @param b      the other list
     * @param j      a region of it
     * @return negative, 0 or positive
     */
    public static int compare(@NotNull Regions a, int i, @NotNull Regions b, int j) {
        int byWorld = Integer.compare(a.worlds[i], b.worlds[j]);
        if (byWorld != 0) {
            return byWorld;
        }
        int byX = Integer.compare(a.xs[i], b.xs[j]);
        return byX != 0 ? byX : Integer.compare(a.zs[i], b.zs[j]);
    }

    /**
     * How many regions there are.
     *
     * @return the count
     */
    public int size() {
        return worlds.length;
    }

    /**
     * Whether a region of a world is listed.
     *
     * @param world a header world index
     * @return {@code true} when some region is in that world
     */
    public boolean holdsWorld(int world) {
        for (int w : worlds) {
            if (w == world) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a listed region can hold a place of a packed-position range of one world. Conservative:
     * a range that spans several chunk x columns reaches every region z of the region x columns it
     * spans.
     *
     * @param world a header world index
     * @param from  the first packed position
     * @param to    the last packed position
     * @return {@code true} when a region may hold a place of the range
     */
    public boolean meets(int world, long from, long to) {
        if (from > to) {
            return false;
        }
        int fromChunkX = BlockPositions.chunkX(from);
        int toChunkX = BlockPositions.chunkX(to);
        int minX = region(fromChunkX);
        int maxX = region(toChunkX);
        boolean oneColumn = fromChunkX == toChunkX;
        int minZ = oneColumn ? region(BlockPositions.chunkZ(from)) : Integer.MIN_VALUE;
        int maxZ = oneColumn ? region(BlockPositions.chunkZ(to)) : Integer.MAX_VALUE;
        for (int i = 0; i < worlds.length; i++) {
            if (worlds[i] == world && xs[i] >= minX && xs[i] <= maxX && zs[i] >= minZ && zs[i] <= maxZ) {
                return true;
            }
        }
        return false;
    }

    private static long key(int world, int x, int z) {
        return (long) world << 40 | (long) (x + (1 << 19)) << 20 | (z + (1 << 19));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Regions regions && Arrays.equals(worlds, regions.worlds)
                && Arrays.equals(xs, regions.xs) && Arrays.equals(zs, regions.zs);
    }

    @Override
    public int hashCode() {
        return (Arrays.hashCode(worlds) * 31 + Arrays.hashCode(xs)) * 31 + Arrays.hashCode(zs);
    }

    @Override
    public @NotNull String toString() {
        return "Regions[" + worlds.length + "]";
    }
}
