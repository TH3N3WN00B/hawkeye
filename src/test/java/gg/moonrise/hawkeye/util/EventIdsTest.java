package gg.moonrise.hawkeye.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class EventIdsTest {

    private static final long T = EventIds.EPOCH_MILLIS + 123_456_789L;

    @Test
    public void composesAndDecomposes() {
        long id = EventIds.compose(T, 513, 4095);
        assertEquals(T, EventIds.epochMillis(id));
        assertEquals(513, EventIds.node(id));
        assertTrue(id > 0);
    }

    @Test
    public void aStampBecomesTheSameIdOnItsNode() {
        long stamp = EventIds.stamp(T, 17);
        assertEquals(EventIds.compose(T, 42, 17), EventIds.fromStamp(stamp, 42));
        assertEquals(T, EventIds.stampMillis(stamp));
    }

    @Test
    public void idsOrderByTimeAcrossNodes() {
        assertTrue(EventIds.compose(T, 1023, 4095) < EventIds.compose(T + 1, 1, 0));
    }

    @Test
    public void floorAndCeilingBracketEveryIdOfAMillisecond() {
        assertTrue(EventIds.floor(T) <= EventIds.compose(T, 0, 0));
        assertTrue(EventIds.ceiling(T) >= EventIds.compose(T, EventIds.MAX_NODE, EventIds.MAX_SEQUENCE));
        assertTrue(EventIds.ceiling(T - 1) < EventIds.floor(T));
    }

    @Test
    public void timesBeforeTheEpochClampToZero() {
        assertEquals(0, EventIds.floor(0));
    }

    @Test
    public void refusesOutOfRangeNodes() {
        assertThrows(IllegalArgumentException.class, () -> EventIds.compose(T, 1024, 0));
        assertThrows(IllegalArgumentException.class, () -> EventIds.fromStamp(0, -1));
    }
}
