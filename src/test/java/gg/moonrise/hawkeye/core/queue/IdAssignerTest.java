package gg.moonrise.hawkeye.core.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.hawkeye.entity.event.PendingEvent;
import gg.moonrise.hawkeye.util.EventIds;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

public class IdAssignerTest {

    private static final long SLOT = 100_000;
    private static final Actor ACTOR = Actor.source("nature");

    @Test
    public void slotsAreRankedInTheFixedOrder() {
        IdAssigner assigner = new IdAssigner();
        assigner.add(List.of(
                placed("c", 10, "world", 5),
                placed("a", 20, "nether", 9),
                unplaced("chat2", 30),
                placed("b", 5, "world", -3),
                unplaced("chat1", 1),
                placed("d", 2, "world", 5)));

        List<PendingEvent> taken = assigner.take(closedAt(SLOT), false);

        // Unplaced by capture order; then world name, signed position, capture order.
        assertEquals(List.of("chat1", "chat2", "a", "b", "d", "c"), labels(taken));
        for (int rank = 0; rank < taken.size(); rank++) {
            assertEquals(EventIds.slotStamp(SLOT, rank), taken.get(rank).stamp());
            assertEquals(rank, EventIds.rank(EventIds.fromStamp(taken.get(rank).stamp(), 7)));
            assertEquals(SLOT, EventIds.slot(EventIds.fromStamp(taken.get(rank).stamp(), 7)));
        }
        assertEquals(0, assigner.pending());
    }

    @Test
    public void everyOtherFieldIsKept() {
        IdAssigner assigner = new IdAssigner();
        PendingEvent event = new PendingEvent(stamp(SLOT, 3, 0), "world", 42, ACTOR, Action.CONTAINER_DEPOSIT,
                "minecraft:dirt", "minecraft:stone", 5, new byte[] {1, 2});
        assigner.add(List.of(event));

        PendingEvent taken = assigner.take(closedAt(SLOT), false).getFirst();

        assertEquals(new PendingEvent(EventIds.slotStamp(SLOT, 0), event.world(), event.position(), event.actor(),
                event.action(), event.subject(), event.secondary(), event.amount(), event.data()), taken);
    }

    @Test
    public void theRolledBackFlagIsCarried() {
        IdAssigner assigner = new IdAssigner();
        PendingEvent imported = new PendingEvent(stamp(SLOT, 3, 0), "world", 42, ACTOR, Action.BLOCK_BREAK,
                "minecraft:dirt", "minecraft:air", 0, null, true);
        assigner.add(List.of(imported, placed("live", 1, "world", 43)));

        List<PendingEvent> taken = assigner.take(closedAt(SLOT), false);

        assertEquals(imported.withStamp(EventIds.slotStamp(SLOT, 0)), taken.getFirst());
        assertTrue(taken.getFirst().rolledBack());
        assertFalse(taken.get(1).rolledBack());
    }

    @Test
    public void theFloorSlotIsReadable() {
        IdAssigner assigner = new IdAssigner();
        assertEquals(0, assigner.floorSlot());
        assigner.floor(SLOT);
        assertEquals(SLOT, assigner.floorSlot());
        assigner.floor(SLOT - 5);
        assertEquals(SLOT, assigner.floorSlot(), "the floor never moves down");
        assigner.add(List.of(placed("old", 0, "world", 0)));
        assertEquals(EventIds.slotStamp(SLOT, 0), assigner.take(Long.MAX_VALUE / 2, true).getFirst().stamp(),
                "an event below the floor is numbered in the floor slot");
    }

    @Test
    public void aSlotIsTakenOnlyAfterItsGrace() {
        IdAssigner assigner = new IdAssigner();
        assigner.add(List.of(placed("a", 999, "world", 0), placed("b", 0, "world", 0)));
        assigner.add(List.of(new PendingEvent(stamp(SLOT + 1, 0, 0), "world", 0, ACTOR, Action.BLOCK_PLACE, "next",
                null, 0, null)));

        assertTrue(assigner.take(closedAt(SLOT) - 1, false).isEmpty());
        assertEquals(3, assigner.pending());

        List<PendingEvent> taken = assigner.take(closedAt(SLOT), false);
        assertEquals(List.of("b", "a"), labels(taken));
        assertEquals(1, assigner.pending());
        assertEquals(List.of("next"), labels(assigner.take(closedAt(SLOT + 1), false)));
    }

    @Test
    public void aForcedTakeReturnsAnOpenSlotAndLaterEventsContinueItsRanks() {
        IdAssigner assigner = new IdAssigner();
        assigner.add(List.of(placed("a", 1, "world", 0), placed("b", 2, "world", 0)));

        List<PendingEvent> first = assigner.take(closedAt(SLOT) - 900, true);
        assertEquals(List.of(EventIds.slotStamp(SLOT, 0), EventIds.slotStamp(SLOT, 1)), stamps(first));

        // An event placed first in the fixed order still comes after the ranks already issued.
        assigner.add(List.of(placed("c", 3, "aaa", -100), unplaced("chat", 4)));
        List<PendingEvent> second = assigner.take(closedAt(SLOT), false);
        assertEquals(List.of("chat", "c"), labels(second));
        assertEquals(List.of(EventIds.slotStamp(SLOT, 2), EventIds.slotStamp(SLOT, 3)), stamps(second));
    }

    @Test
    public void aLateEventForATakenSlotContinuesItsRanks() {
        IdAssigner assigner = new IdAssigner();
        assigner.add(List.of(placed("a", 1, "world", 0)));
        assigner.take(closedAt(SLOT), false);
        assigner.add(List.of(new PendingEvent(stamp(SLOT + 1, 0, 0), "world", 0, ACTOR, Action.BLOCK_PLACE, "next",
                null, 0, null)));
        assigner.take(closedAt(SLOT + 1), false);

        assigner.add(List.of(placed("late", 900, "world", 0)));
        List<PendingEvent> taken = assigner.take(closedAt(SLOT + 1), false);

        assertEquals(List.of(EventIds.slotStamp(SLOT, 1)), stamps(taken));
    }

    @Test
    public void anEventOlderThanTheRetainedCountersGoesToTheOldestRetainedSlot() {
        IdAssigner assigner = new IdAssigner();
        int slots = IdAssigner.RETAINED_SLOTS + 5;
        for (int i = 0; i < slots; i++) {
            assigner.add(List.of(new PendingEvent(stamp(SLOT + i, 0, 0), "world", 0, ACTOR, Action.BLOCK_PLACE,
                    "s" + i, null, 0, null)));
            assigner.take(closedAt(SLOT + i), false);
        }
        long oldestRetained = SLOT + slots - IdAssigner.RETAINED_SLOTS;

        assigner.add(List.of(placed("ancient", 0, "world", 0)));
        List<PendingEvent> taken = assigner.take(closedAt(SLOT + slots), false);

        // The oldest retained slot issued rank 0 already, so the late event gets rank 1 there.
        assertEquals(List.of(EventIds.slotStamp(oldestRetained, 1)), stamps(taken));
    }

    @Test
    public void theFloorMovesEarlierEventsIntoTheFloorSlot() {
        IdAssigner assigner = new IdAssigner();
        assigner.add(List.of(placed("before", 1, "world", 0)));
        assigner.floor(SLOT + 3);
        assigner.add(List.of(placed("after", 2, "world", 0)));
        assigner.floor(SLOT + 1);

        assertTrue(assigner.take(closedAt(SLOT + 2), false).isEmpty());
        List<PendingEvent> taken = assigner.take(closedAt(SLOT + 3), false);

        assertEquals(List.of("before", "after"), labels(taken));
        assertEquals(List.of(EventIds.slotStamp(SLOT + 3, 0), EventIds.slotStamp(SLOT + 3, 1)), stamps(taken));
    }

    @Test
    public void theResultDoesNotDependOnTheOrderEventsWereAdded() {
        List<PendingEvent> events = new ArrayList<>();
        Random random = new Random(42);
        for (int i = 0; i < 500; i++) {
            long slot = SLOT + random.nextInt(3);
            long stamp = stamp(slot, random.nextInt(1000), i);
            String world = random.nextBoolean() ? "world" : "world_nether";
            events.add(random.nextInt(5) == 0
                    ? new PendingEvent(stamp, world, 0, ACTOR, Action.CHAT, "e" + i, null, 0, null)
                    : new PendingEvent(stamp, world, random.nextInt(21) - 10, ACTOR, Action.BLOCK_BREAK, "e" + i,
                            null, 0, null));
        }
        List<PendingEvent> expected = takeAll(events);
        for (int round = 0; round < 5; round++) {
            List<PendingEvent> shuffled = new ArrayList<>(events);
            Collections.shuffle(shuffled, new Random(round));
            List<PendingEvent> actual = takeAll(shuffled);
            assertEquals(labels(expected), labels(actual));
            assertEquals(stamps(expected), stamps(actual));
        }
        Set<Long> distinct = new HashSet<>(stamps(expected));
        assertEquals(events.size(), distinct.size());
    }

    @Test
    public void eventsAtOnePositionKeepTheirCaptureOrder() {
        IdAssigner assigner = new IdAssigner();
        assigner.add(List.of(placed("third", 30, "world", 7), placed("first", 10, "world", 7),
                placed("second", 20, "world", 7)));

        assertEquals(List.of("first", "second", "third"), labels(assigner.take(closedAt(SLOT), false)));
    }

    @Test
    public void aFullSlotSpillsIntoTheNext() {
        IdAssigner assigner = new IdAssigner(2);
        assigner.add(List.of(placed("a", 1, "world", 0), placed("b", 2, "world", 1), placed("c", 3, "world", 2),
                placed("d", 4, "world", 3), placed("e", 5, "world", 4)));
        assigner.add(List.of(new PendingEvent(stamp(SLOT + 1, 0, 0), "world", 10, ACTOR, Action.BLOCK_PLACE,
                "next", null, 0, null)));

        List<PendingEvent> first = assigner.take(closedAt(SLOT), false);
        assertEquals(List.of("a", "b", "c"), labels(first));
        assertEquals(3, assigner.pending());

        List<PendingEvent> second = assigner.take(closedAt(SLOT + 1), false);
        assertEquals(List.of("d", "e", "next"), labels(second));
        assertEquals(List.of(EventIds.slotStamp(SLOT + 1, 0), EventIds.slotStamp(SLOT + 1, 1),
                EventIds.slotStamp(SLOT + 1, 2)), stamps(second));
    }

    private static @NotNull List<PendingEvent> takeAll(@NotNull List<PendingEvent> events) {
        IdAssigner assigner = new IdAssigner();
        assigner.add(events);
        return assigner.take(closedAt(SLOT + 10), false);
    }

    @Test
    public void aSlotFarAheadOfTheClockIsNotHeldBack() {
        // The database's newest id lies ten minutes in the future (another server's clock runs fast).
        IdAssigner assigner = new IdAssigner();
        assigner.floor(SLOT + 600);
        long now = EventIds.EPOCH_MILLIS + SLOT * EventIds.SLOT_MILLIS + 400;
        assigner.add(List.of(placed("b", 300, "world", 2), placed("a", 100, "world", 1)));

        List<PendingEvent> first = assigner.take(now, false);

        assertEquals(List.of("a", "b"), labels(first));
        assertEquals(List.of(EventIds.slotStamp(SLOT + 600, 0), EventIds.slotStamp(SLOT + 600, 1)), stamps(first));

        // Later events land in the same slot until the clock reaches it, and continue its ranks.
        assigner.add(List.of(placed("c", 900, "world", 0)));
        assertEquals(List.of(EventIds.slotStamp(SLOT + 600, 2)), stamps(assigner.take(now + 1000, false)));
        assertEquals(0, assigner.pending());
    }

    @Test
    public void theSecondAfterTheCurrentOneStillWaitsForItsGrace() {
        // A burst can stamp a few events into the next second; they wait like any other open second.
        IdAssigner assigner = new IdAssigner();
        long now = EventIds.EPOCH_MILLIS + SLOT * EventIds.SLOT_MILLIS + 999;
        assigner.add(List.of(new PendingEvent(stamp(SLOT + 1, 0, 0), "world", 0, ACTOR, Action.BLOCK_PLACE, "next",
                null, 0, null)));

        assertTrue(assigner.take(now, false).isEmpty());
        assertEquals(List.of("next"), labels(assigner.take(closedAt(SLOT + 1), false)));
    }

    private static long closedAt(long slot) {
        return EventIds.EPOCH_MILLIS + (slot + 1) * EventIds.SLOT_MILLIS + IdAssigner.GRACE_MILLIS;
    }

    private static long stamp(long slot, int millis, int sequence) {
        return EventIds.stamp(EventIds.EPOCH_MILLIS + slot * EventIds.SLOT_MILLIS + millis, sequence);
    }

    private static @NotNull PendingEvent placed(@NotNull String label, int millis, @NotNull String world,
            long position) {
        return new PendingEvent(stamp(SLOT, millis, 0), world, position, ACTOR, Action.BLOCK_PLACE, label, null, 0,
                null);
    }

    private static @NotNull PendingEvent unplaced(@NotNull String label, int millis) {
        return new PendingEvent(stamp(SLOT, millis, 0), "zzz", -999, ACTOR, Action.CHAT, label, null, 0, null);
    }

    private static @NotNull List<String> labels(@NotNull List<PendingEvent> events) {
        return events.stream().map(PendingEvent::subject).toList();
    }

    private static @NotNull List<Long> stamps(@NotNull List<PendingEvent> events) {
        return events.stream().map(PendingEvent::stamp).toList();
    }
}
