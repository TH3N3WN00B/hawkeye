package gg.moonrise.hawkeye.util.position;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

public class BlockPositionsTest {

    private static final int[] SAMPLES = {BlockPositions.MIN_HORIZONTAL, -30_000_000, -65_537, -17, -16, -15, -1, 0,
            1, 15, 16, 17, 65_535, 29_999_999, BlockPositions.MAX_HORIZONTAL};

    @Test
    public void roundTripsEveryCorner() {
        for (int x : SAMPLES) {
            for (int z : SAMPLES) {
                for (int y : new int[] {BlockPositions.MIN_Y, -64, 0, 319, BlockPositions.MAX_Y}) {
                    long position = BlockPositions.pack(x, y, z);
                    assertEquals(x, BlockPositions.x(position), "x of " + x + "," + y + "," + z);
                    assertEquals(y, BlockPositions.y(position), "y of " + x + "," + y + "," + z);
                    assertEquals(z, BlockPositions.z(position), "z of " + x + "," + y + "," + z);
                    assertEquals(x >> 4, BlockPositions.chunkX(position));
                    assertEquals(z >> 4, BlockPositions.chunkZ(position));
                }
            }
        }
    }

    @Test
    public void ordersChunkXFirstThenChunkZ() {
        assertTrue(BlockPositions.pack(-16, 0, 500) < BlockPositions.pack(0, 0, -500));
        assertTrue(BlockPositions.pack(0, 0, -16) < BlockPositions.pack(0, 0, 0));
    }

    @Test
    public void aChunkRunIsOneRangeThatHoldsExactlyItsBlocks() {
        List<BlockPositions.Range> ranges = BlockPositions.columnRanges(-1, -2, 0, 1);
        assertEquals(2, ranges.size());
        BlockPositions.Range first = ranges.getFirst();
        assertTrue(contains(first, BlockPositions.pack(-16, -2048, -32)));
        assertTrue(contains(first, BlockPositions.pack(-1, 2047, 31)));
        assertTrue(!contains(first, BlockPositions.pack(-1, 0, 32)));
        assertTrue(!contains(first, BlockPositions.pack(-1, 0, -33)));
        assertTrue(!contains(first, BlockPositions.pack(0, 0, 0)));
    }

    @Test
    public void refusesPositionsOutsideTheRange() {
        assertThrows(IllegalArgumentException.class, () -> BlockPositions.pack(0, 2048, 0));
        assertThrows(IllegalArgumentException.class, () -> BlockPositions.pack(BlockPositions.MAX_HORIZONTAL + 1, 0,
                0));
    }

    private static boolean contains(BlockPositions.Range range, long position) {
        return position >= range.from() && position <= range.to();
    }
}
