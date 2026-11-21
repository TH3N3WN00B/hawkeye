package gg.moonrise.hawkeye.util.event;

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

    @Test
    public void aSlotStampRoundTripsThroughSlotAndRank() {
        long slot = (T - EventIds.EPOCH_MILLIS) / EventIds.SLOT_MILLIS;
        for (int rank : new int[] {0, 1, 4095, 4096, 4097, 1_000_000, EventIds.MAX_RANK}) {
            long id = EventIds.fromStamp(EventIds.slotStamp(slot, rank), 7);
            assertEquals(slot, EventIds.slot(id));
            assertEquals(rank, EventIds.rank(id));
            assertEquals(7, EventIds.node(id));
            assertEquals(id, EventIds.fromSlot(slot, 7, rank));
            assertEquals(slot, EventIds.stampSlot(EventIds.slotStamp(slot, rank)));
        }
    }

    @Test
    public void ranksOrderIdsInsideASlotAndNeverLeaveIt() {
        long slot = 1_000_000;
        long first = EventIds.fromSlot(slot, 1, 0);
        long last = EventIds.fromSlot(slot, 1, EventIds.MAX_RANK);
        assertTrue(first < EventIds.fromSlot(slot, 1, 1));
        assertTrue(EventIds.fromSlot(slot, 1, 4095) < EventIds.fromSlot(slot, 1, 4096));
        assertTrue(last < EventIds.fromSlot(slot + 1, 1, 0));
        assertEquals(EventIds.EPOCH_MILLIS + slot * EventIds.SLOT_MILLIS, EventIds.epochMillis(first));
        assertEquals(EventIds.EPOCH_MILLIS + slot * EventIds.SLOT_MILLIS + 999, EventIds.epochMillis(last));
    }

    @Test
    public void anIdOffTheGridStillHasASlotAndARank() {
        long id = EventIds.compose(T, 3, 17);
        long millis = T - EventIds.EPOCH_MILLIS;
        assertEquals(millis / 1000, EventIds.slot(id));
        assertEquals((int) ((millis % 1000) << EventIds.SEQUENCE_BITS | 17), EventIds.rank(id));
        assertEquals(id, EventIds.fromSlot(EventIds.slot(id), 3, EventIds.rank(id)));
    }

    @Test
    public void slotStampsRejectWhatDoesNotFit() {
        assertThrows(IllegalArgumentException.class, () -> EventIds.slotStamp(1, EventIds.MAX_RANK + 1));
        assertThrows(IllegalArgumentException.class, () -> EventIds.slotStamp(1, -1));
        assertThrows(IllegalArgumentException.class, () -> EventIds.slotStamp(-1, 0));
    }
}
