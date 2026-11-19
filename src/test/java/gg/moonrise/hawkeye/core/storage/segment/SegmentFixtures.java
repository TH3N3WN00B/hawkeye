package gg.moonrise.hawkeye.core.storage.segment;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import gg.moonrise.hawkeye.core.repository.event.EventRow;
import gg.moonrise.hawkeye.util.BlockPositions;
import gg.moonrise.hawkeye.util.EventIds;
import gg.moonrise.hawkeye.util.EventOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.jetbrains.annotations.NotNull;

/** Random events for the segment tests, with ids issued the way the writer issues them. */
public final class SegmentFixtures {

    /** A world order that is not ascending. */
    public static final List<Integer> WORLD_ORDER = List.of(7, 2, 11, 5);

    private SegmentFixtures() {
    }

    /**
     * An event before it has an id.
     *
     * @param slot      its second
     * @param node      its server
     * @param capture   its capture order
     * @param world     its world
     * @param position  its packed position
     * @param actor     its actor
     * @param action    its action code
     * @param subject   its subject
     * @param secondary its secondary
     * @param amount    its amount
     * @param flags     its flags
     * @param data      its payload
     */
    public record Draft(long slot, int node, long capture, int world, long position, int actor, int action,
            int subject, int secondary, int amount, int flags, byte[] data) {

        /**
         * The row with an id.
         *
         * @param id the id
         * @return the row
         */
        public @NotNull EventRow withId(long id) {
            return new EventRow(id, world, position, actor, action, subject, secondary, amount, flags, data);
        }
    }

    /**
     * Random drafts covering the format's corners.
     *
     * @param random   the source
     * @param placed   how many placed events
     * @param unplaced how many unplaced events
     * @return the drafts, in capture order
     */
    public static @NotNull List<Draft> drafts(@NotNull Random random, int placed, int unplaced) {
        List<Long> hotSpots = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            hotSpots.add(randomPosition(random));
        }
        List<Draft> drafts = new ArrayList<>(placed + unplaced);
        long slot = 50_000 + random.nextInt(1000);
        int remainingPlaced = placed;
        int remainingUnplaced = unplaced;
        long capture = 0;
        while (remainingPlaced + remainingUnplaced > 0) {
            if (random.nextInt(4) == 0) {
                slot += random.nextInt(3);
            }
            boolean isPlaced = random.nextInt(remainingPlaced + remainingUnplaced) < remainingPlaced;
            int action;
            if (isPlaced) {
                remainingPlaced--;
                action = random.nextBoolean() ? random.nextInt(EventOrder.FIRST_UNPLACED_CODE)
                        : EventOrder.END_UNPLACED_CODE + random.nextInt(80);
            } else {
                remainingUnplaced--;
                action = EventOrder.FIRST_UNPLACED_CODE
                        + random.nextInt(EventOrder.END_UNPLACED_CODE - EventOrder.FIRST_UNPLACED_CODE);
            }
            long position = random.nextInt(3) == 0 ? hotSpots.get(random.nextInt(hotSpots.size()))
                    : randomPosition(random);
            int subject = random.nextInt(4) == 0 ? 0 : 1 + random.nextInt(300);
            int secondary = random.nextInt(3) == 0 ? 1 + random.nextInt(50) : 0;
            int amount = random.nextInt(3) == 0 ? 0 : random.nextInt(5000);
            int flags = random.nextInt(10) == 0 ? EventRow.ROLLED_BACK : random.nextInt(20) == 0 ? 6 : 0;
            drafts.add(new Draft(slot, 1 + random.nextInt(3), capture++, WORLD_ORDER.get(random.nextInt(4)), position,
                    1 + random.nextInt(random.nextInt(8) == 0 ? 2000 : 30), action, subject, secondary, amount, flags,
                    data(random)));
        }
        return drafts;
    }

    private static byte[] data(@NotNull Random random) {
        int kind = random.nextInt(100);
        if (kind < 60) {
            return null;
        }
        byte[] data = new byte[kind < 65 ? 0 : kind < 98 ? 1 + random.nextInt(40) : 2000 + random.nextInt(4000)];
        random.nextBytes(data);
        return data;
    }

    private static long randomPosition(@NotNull Random random) {
        int x = random.nextInt(4000) - 2000;
        int z = random.nextInt(4000) - 2000;
        int kind = random.nextInt(20);
        int y = kind == 0 ? BlockPositions.MIN_Y : kind == 1 ? BlockPositions.MAX_Y : random.nextInt(384) - 64;
        if (kind == 2) {
            x = BlockPositions.MIN_HORIZONTAL;
            z = BlockPositions.MAX_HORIZONTAL;
        }
        return BlockPositions.pack(x, y, z);
    }

    /**
     * Ids issued the way the writer issues them: per (slot, node), unplaced events by capture order,
     * then placed events by world order, position and capture order, ranked 0, 1, 2, ...
     *
     * @param drafts     the drafts
     * @param worldOrder the world order
     * @return the rows
     */
    public static @NotNull List<EventRow> canonical(@NotNull List<Draft> drafts, @NotNull List<Integer> worldOrder) {
        Map<Long, List<Draft>> bySecond = new HashMap<>();
        for (Draft draft : drafts) {
            bySecond.computeIfAbsent(draft.slot() * 1024 + draft.node(), key -> new ArrayList<>()).add(draft);
        }
        Comparator<Draft> order = Comparator.<Draft>comparingInt(d -> EventOrder.byPlace(d.action()) ? 1 : 0)
                .thenComparingInt(d -> EventOrder.byPlace(d.action()) ? worldOrder.indexOf(d.world()) : 0)
                .thenComparingLong(d -> EventOrder.byPlace(d.action()) ? d.position() : 0)
                .thenComparingLong(Draft::capture);
        List<EventRow> rows = new ArrayList<>(drafts.size());
        for (List<Draft> second : bySecond.values()) {
            second.sort(order);
            for (int rank = 0; rank < second.size(); rank++) {
                Draft draft = second.get(rank);
                rows.add(draft.withId(EventIds.fromSlot(draft.slot(), draft.node(), rank)));
            }
        }
        return rows;
    }

    /**
     * What decoding should give: the rows sorted by id, flags reduced to the rolled-back bit.
     *
     * @param rows the encoded rows
     * @return the expected rows
     */
    public static @NotNull List<EventRow> expected(@NotNull List<EventRow> rows) {
        return rows.stream().sorted(Comparator.comparingLong(EventRow::id))
                .map(row -> new EventRow(row.id(), row.world(), row.position(), row.actor(), row.action(),
                        row.subject(), row.secondary(), row.amount(), row.flags() & EventRow.ROLLED_BACK, row.data()))
                .toList();
    }

    /**
     * Asserts two row lists are equal, comparing payloads by content.
     *
     * @param expected the expected rows
     * @param actual   the actual rows
     */
    public static void assertRows(@NotNull List<EventRow> expected, @NotNull List<EventRow> actual) {
        assertEquals(expected.size(), actual.size(), "row count");
        for (int i = 0; i < expected.size(); i++) {
            EventRow want = expected.get(i);
            EventRow got = actual.get(i);
            assertEquals(strip(want), strip(got), "row " + i);
            if (want.data() == null) {
                assertEquals(null, got.data(), "row " + i + " data");
            } else {
                assertArrayEquals(want.data(), got.data(), "row " + i + " data");
            }
        }
    }

    private static @NotNull EventRow strip(@NotNull EventRow row) {
        return new EventRow(row.id(), row.world(), row.position(), row.actor(), row.action(), row.subject(),
                row.secondary(), row.amount(), row.flags(), null);
    }
}
