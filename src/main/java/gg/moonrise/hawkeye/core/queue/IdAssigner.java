package gg.moonrise.hawkeye.core.queue;

import gg.moonrise.hawkeye.entity.event.PendingEvent;
import gg.moonrise.hawkeye.util.EventIds;
import gg.moonrise.hawkeye.util.EventOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jetbrains.annotations.NotNull;

/**
 * Renumbers captured events onto the one-second grid ({@link EventIds#slotStamp(long, int)}). Events
 * are buffered by slot until the slot has closed, then sorted in {@link EventOrder}'s fixed order and
 * ranked 0, 1, 2, ... so the archive can rebuild every rank from the stored rows alone.
 *
 * <p>Each slot keeps its rank counter after it is taken, so events of that slot that arrive later (a
 * forced partial take, a late event) continue the numbering and no rank is issued twice. Only the
 * newest {@link #RETAINED_SLOTS} counters are kept; dropping older ones raises the floor, so an event
 * stamped for a forgotten slot is numbered in the oldest retained slot instead.
 *
 * <p>Used by the writer thread only. It never reads a clock: the caller passes the time, so the result
 * depends on its input alone. {@link #pending()} may be read from any thread.
 */
public final class IdAssigner {

    /** How long after a second ends its slot stays open: game threads may still be queueing its events. */
    public static final long GRACE_MILLIS = 250;

    /** How many of the newest slots keep their rank counters. */
    public static final int RETAINED_SLOTS = 120;

    /**
     * The fixed order of one server's events of a slot: events not ordered by place first, by capture
     * stamp; then every other event by world, packed position and capture stamp. The order they were
     * added breaks any remaining tie, so the sort is total and deterministic.
     */
    private static final Comparator<Buffered> ORDER = Comparator
            .comparing((Buffered entry) -> EventOrder.byPlace(entry.event().action().code()))
            .thenComparing((first, second) -> {
                if (!EventOrder.byPlace(first.event().action().code())) {
                    return 0;
                }
                int world = first.event().world().compareTo(second.event().world());
                return world != 0 ? world : Long.compare(first.event().position(), second.event().position());
            })
            .thenComparingLong(entry -> entry.event().stamp())
            .thenComparingLong(Buffered::sequence);

    private final int maxRank;
    private final TreeMap<Long, List<Buffered>> buffered = new TreeMap<>();
    private final TreeMap<Long, Integer> counters = new TreeMap<>();
    private long floor;
    private long sequence;
    private volatile int pending;

    /** An assigner with the full {@link EventIds#MAX_RANK} ranks per slot. */
    public IdAssigner() {
        this(EventIds.MAX_RANK);
    }

    /**
     * An assigner with a smaller rank limit, so tests can fill a slot quickly.
     *
     * @param maxRank the largest rank issued in one slot
     */
    IdAssigner(int maxRank) {
        this.maxRank = maxRank;
    }

    /**
     * Buffers captured events by slot: the slot of their capture stamp, or the floor when that is later.
     *
     * @param events the events, with capture stamps
     */
    public void add(@NotNull List<PendingEvent> events) {
        for (PendingEvent event : events) {
            long slot = Math.max(EventIds.stampSlot(event.stamp()), floor);
            buffered.computeIfAbsent(slot, key -> new ArrayList<>()).add(new Buffered(event, sequence++));
        }
        pending += events.size();
    }

    /**
     * Removes the events of every closed slot and gives them their final stamps.
     *
     * @param nowMillis the current time in Unix milliseconds
     * @param all       {@code true} to take every slot, closed or not (a flush or shutdown)
     * @return copies of the events whose stamp is {@code slotStamp(slot, rank)}, in id order
     */
    public @NotNull List<PendingEvent> take(long nowMillis, boolean all) {
        List<PendingEvent> out = new ArrayList<>();
        while (!buffered.isEmpty()) {
            long slot = buffered.firstKey();
            if (!all && !closed(slot, nowMillis)) {
                break;
            }
            List<Buffered> entries = buffered.pollFirstEntry().getValue();
            entries.sort(ORDER);
            int rank = counters.getOrDefault(slot, 0);
            int numbered = 0;
            for (Buffered entry : entries) {
                if (rank > maxRank) {
                    break;
                }
                PendingEvent event = entry.event();
                out.add(new PendingEvent(EventIds.slotStamp(slot, rank++), event.world(), event.position(),
                        event.actor(), event.action(), event.subject(), event.secondary(), event.amount(),
                        event.data()));
                numbered++;
            }
            counters.put(slot, rank);
            pending -= numbered;
            if (numbered < entries.size()) {
                // The slot is full: the rest moves on to the next slot, which is never older than they are.
                buffered.computeIfAbsent(slot + 1, key -> new ArrayList<>())
                        .addAll(entries.subList(numbered, entries.size()));
            }
        }
        forgetOldCounters();
        return out;
    }

    /**
     * Raises the floor: events of earlier slots are numbered in the floor slot. Never lowers it.
     *
     * @param slot the smallest slot to issue ranks in
     */
    public void floor(long slot) {
        if (slot > floor) {
            floor = slot;
            rebase();
        }
    }

    /**
     * Buffered events not yet taken.
     *
     * @return the count
     */
    public int pending() {
        return pending;
    }

    /**
     * Whether a slot can be numbered now: its second is over and the grace has passed, or it lies more
     * than a second ahead of the clock. Only the floor puts events that far ahead (a database whose
     * newest id is in the future, because another server's clock runs fast), and waiting for the clock
     * to reach that slot would hold them back for as long as the clocks disagree; they are numbered at
     * once and later arrivals continue the slot's ranks.
     */
    private static boolean closed(long slot, long nowMillis) {
        long end = EventIds.EPOCH_MILLIS + (slot + 1) * EventIds.SLOT_MILLIS;
        return nowMillis >= end + GRACE_MILLIS || nowMillis < end - 2L * EventIds.SLOT_MILLIS;
    }

    private void forgetOldCounters() {
        if (counters.size() <= RETAINED_SLOTS) {
            return;
        }
        while (counters.size() > RETAINED_SLOTS) {
            counters.pollFirstEntry();
        }
        floor(counters.firstKey());
    }

    /** Moves buffered events of slots below the floor into the floor slot. */
    private void rebase() {
        Map<Long, List<Buffered>> below = buffered.headMap(floor);
        if (below.isEmpty()) {
            return;
        }
        List<Buffered> moved = new ArrayList<>();
        below.values().forEach(moved::addAll);
        below.clear();
        buffered.computeIfAbsent(floor, key -> new ArrayList<>()).addAll(moved);
    }

    /**
     * A buffered event and the order it was added in.
     *
     * @param event    the event
     * @param sequence the running count of added events, the last tie-breaker
     */
    private record Buffered(@NotNull PendingEvent event, long sequence) {
    }
}
