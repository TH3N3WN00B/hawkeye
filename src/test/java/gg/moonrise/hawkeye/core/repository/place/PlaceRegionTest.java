package gg.moonrise.hawkeye.core.repository.place;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.util.BlockPositions;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

public class PlaceRegionTest {

    @Test
    public void keysOrderLikePackedPositionsAndRoundTrip() {
        long[] positions = {
                BlockPositions.pack(-33_000_000, -2048, -33_000_000), BlockPositions.pack(-17, 64, 5),
                BlockPositions.pack(-1, 0, -1), BlockPositions.pack(15, 70, -600), BlockPositions.pack(0, 0, 0),
                BlockPositions.pack(512, 10, 3), BlockPositions.pack(33_000_000, 2047, 33_000_000)};
        for (int i = 0; i < positions.length; i++) {
            long chunk = PlaceKeys.chunk(positions[i]);
            assertEquals(BlockPositions.chunkX(positions[i]), BlockPositions.chunkX(PlaceKeys.base(chunk)));
            assertEquals(BlockPositions.chunkZ(positions[i]), BlockPositions.chunkZ(PlaceKeys.base(chunk)));
            assertEquals(chunk, PlaceKeys.chunkOf(PlaceKeys.region(chunk), PlaceKeys.indexInRegion(chunk)));
            int regionX = BlockPositions.chunkX(positions[i]) >> 5;
            int regionZ = BlockPositions.chunkZ(positions[i]) >> 5;
            assertEquals(PlaceKeys.region(chunk), PlaceKeys.regionOf(regionX, regionZ));
            assertTrue(PlaceKeys.regionOf(regionX, regionZ) < PlaceKeys.regionOf(regionX, regionZ + 1)
                    && PlaceKeys.regionOf(regionX, regionZ + 1) < PlaceKeys.regionOf(regionX + 1, -65_536),
                    "region keys order by region x, then z");
            if (i > 0) {
                long previous = PlaceKeys.chunk(positions[i - 1]);
                assertTrue(previous < chunk, "chunk keys order like positions");
            }
        }
    }

    @Test
    public void roundTripsAndTrims() {
        PlaceRegion region = new PlaceRegion(3, PlaceKeys.region(PlaceKeys.chunk(BlockPositions.pack(-40, 0, 70))));
        for (long id = 10; id <= 40; id++) {
            region.segmentsOf(0).add(id);
        }
        region.segmentsOf(5).add(12);
        region.segmentsOf(1_023).add(90);
        region.segmentsOf(1_023).add(41);
        region.setBusy(0, true);
        region.setBusy(5, true);
        PlaceRegion decoded = PlaceRegion.fromBody(3, region.region(), region.toBody(9));
        assertEquals(region, decoded);
        assertEquals(90, decoded.newest());
        assertTrue(decoded.busy(0) && !decoded.busy(1_023));

        Set<Integer> dropped = decoded.trim(13);
        assertEquals(Set.of(5), dropped, "a busy chunk left with nothing loses its row");
        assertEquals(Optional.empty(), decoded.find(5));
        assertEquals(IdRuns.range(13, 40), decoded.find(0).orElseThrow());
        decoded.removeEverywhere(90);
        decoded.trim(13);
        assertEquals(IdRuns.of(41), decoded.find(1_023).orElseThrow());
        decoded.removeEverywhere(41);
        decoded.trim(41);
        assertTrue(decoded.isEmpty());
        assertFalse(decoded.busy(0));
    }

    @Test
    public void aBusyChunkRoundTripsAndTrimsItsBuckets() {
        PlaceChunk chunk = new PlaceChunk(1, PlaceKeys.chunk(BlockPositions.pack(100, 64, 100)));
        for (int segment = 1; segment < 200; segment++) {
            chunk.add(PlaceKeys.local(BlockPositions.pack(100 + segment % 3, 64, 100)), segment);
        }
        chunk.add(PlaceKeys.local(BlockPositions.pack(111, -60, 103)), 3);
        chunk.add(PlaceKeys.local(BlockPositions.pack(111, -60, 103)), 150);
        PlaceChunk decoded = PlaceChunk.fromBody(1, chunk.chunk(), chunk.toBody(9));
        assertEquals(chunk, decoded);
        int lonely = PlaceKeys.local(BlockPositions.pack(111, -60, 103));
        assertEquals(IdRuns.of(0, 9), decoded.positions().get(lonely));
        assertEquals(IdRuns.of(0, 9), PlaceChunk.bucketsWhere(chunk.toBody(19), local -> local == lonely, lonely));
        assertEquals(new IdRuns(), PlaceChunk.bucketsWhere(chunk.toBody(9), local -> local == lonely, lonely - 1),
                "the read stops at the largest key asked for");

        decoded.trim(16);
        assertEquals(IdRuns.of(9), decoded.positions().get(lonely));
        assertEquals(IdRuns.range(1, 12), decoded.positions().firstEntry().getValue());
        decoded.trim(16 * 13);
        assertTrue(decoded.isEmpty(), "every bucket below the oldest segment's is gone");
    }
}
