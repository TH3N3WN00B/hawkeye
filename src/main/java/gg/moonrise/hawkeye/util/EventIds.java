package gg.moonrise.hawkeye.util;

/**
 * The 64-bit event id: {@code [0][41 bits ms since 2025-01-01Z][10 bits node][12 bits sequence]}.
 * The id is the primary key and the timestamp at once, so a time window is an id range and
 * {@code ORDER BY id DESC} is newest-first across every server (docs/architecture.md, "Event id").
 *
 * <p>Capture produces a <em>stamp</em> ({@code [time][sequence]}, no node) on the game thread. The
 * writer replaces it with a stamp on the one-second grid ({@link #slotStamp(long, int)}): every event
 * of a second gets that second (its <em>slot</em>) and a <em>rank</em>, its place among the server's
 * events of that second in {@link EventOrder}. An id then says "second, server, rank", and the archive
 * stores the second alone. The rank fills the sequence bits first and then the millisecond bits, so a
 * second holds {@link #MAX_RANK} events and an id is still never more than a second off.
 */
public final class EventIds {

    /** 2025-01-01T00:00:00Z: the zero of the time field. */
    public static final long EPOCH_MILLIS = 1_735_689_600_000L;

    /** Bits of the node id. */
    public static final int NODE_BITS = 10;

    /** Bits of the per-millisecond sequence. */
    public static final int SEQUENCE_BITS = 12;

    /** The largest node id; node ids run from 1 to this value, 0 is never assigned to a server. */
    public static final int MAX_NODE = (1 << NODE_BITS) - 1;

    /** The largest sequence number within one millisecond. */
    public static final int MAX_SEQUENCE = (1 << SEQUENCE_BITS) - 1;

    /** Ids are issued on a grid of this many milliseconds: one slot is one second. */
    public static final int SLOT_MILLIS = 1000;

    /** The largest rank inside a slot: the sequence bits of every millisecond of it. */
    public static final int MAX_RANK = SLOT_MILLIS * (MAX_SEQUENCE + 1) - 1;

    private static final int TIME_SHIFT = NODE_BITS + SEQUENCE_BITS;
    private static final long MAX_TIME_OFFSET = (1L << 41) - 1;

    private EventIds() {
    }

    /**
     * Builds an id.
     *
     * @param epochMillis the event time in Unix milliseconds
     * @param node        the server's node id (0 to {@link #MAX_NODE})
     * @param sequence    the sequence within the millisecond (0 to {@link #MAX_SEQUENCE})
     * @return the id
     */
    public static long compose(long epochMillis, int node, int sequence) {
        if (node < 0 || node > MAX_NODE) {
            throw new IllegalArgumentException("node out of range: " + node);
        }
        if (sequence < 0 || sequence > MAX_SEQUENCE) {
            throw new IllegalArgumentException("sequence out of range: " + sequence);
        }
        return (timeOffset(epochMillis) << TIME_SHIFT) | ((long) node << SEQUENCE_BITS) | sequence;
    }

    /**
     * Turns a capture stamp into an id.
     *
     * @param stamp a stamp from {@code core.time.EventClock}: {@code (time offset << 12) | sequence}
     * @param node  the server's node id
     * @return the id
     */
    public static long fromStamp(long stamp, int node) {
        if (node < 0 || node > MAX_NODE) {
            throw new IllegalArgumentException("node out of range: " + node);
        }
        long time = stamp >>> SEQUENCE_BITS;
        return (time << TIME_SHIFT) | ((long) node << SEQUENCE_BITS) | (stamp & MAX_SEQUENCE);
    }

    /**
     * Builds a capture stamp.
     *
     * @param epochMillis the event time in Unix milliseconds
     * @param sequence    the sequence within the millisecond
     * @return the stamp
     */
    public static long stamp(long epochMillis, int sequence) {
        return (timeOffset(epochMillis) << SEQUENCE_BITS) | (sequence & MAX_SEQUENCE);
    }

    /**
     * The time of a capture stamp.
     *
     * @param stamp the stamp
     * @return Unix milliseconds
     */
    public static long stampMillis(long stamp) {
        return (stamp >>> SEQUENCE_BITS) + EPOCH_MILLIS;
    }

    /**
     * The slot of a stamp: its second, counted from the Hawkeye epoch.
     *
     * @param stamp a stamp
     * @return the slot
     */
    public static long stampSlot(long stamp) {
        return (stamp >>> SEQUENCE_BITS) / SLOT_MILLIS;
    }

    /**
     * The stamp of the event with a rank in a slot.
     *
     * @param slot the slot (seconds since the Hawkeye epoch)
     * @param rank the event's place among its server's events of that slot (0 to {@link #MAX_RANK})
     * @return the stamp
     */
    public static long slotStamp(long slot, int rank) {
        if (slot < 0 || slot > MAX_TIME_OFFSET / SLOT_MILLIS) {
            throw new IllegalArgumentException("slot out of range: " + slot);
        }
        if (rank < 0 || rank > MAX_RANK) {
            throw new IllegalArgumentException("rank out of range: " + rank);
        }
        long time = slot * SLOT_MILLIS + (rank >>> SEQUENCE_BITS);
        return (time << SEQUENCE_BITS) | (rank & MAX_SEQUENCE);
    }

    /**
     * The slot of an id: its second, counted from the Hawkeye epoch.
     *
     * @param id the id
     * @return the slot
     */
    public static long slot(long id) {
        return (id >>> TIME_SHIFT) / SLOT_MILLIS;
    }

    /**
     * The rank of an id inside its slot: the millisecond within the slot and the sequence, as one
     * number. Ids issued on the grid count 0, 1, 2, ... per server and slot.
     *
     * @param id the id
     * @return the rank (0 to {@link #MAX_RANK})
     */
    public static int rank(long id) {
        long millis = (id >>> TIME_SHIFT) % SLOT_MILLIS;
        return (int) ((millis << SEQUENCE_BITS) | (id & MAX_SEQUENCE));
    }

    /**
     * Builds an id from its slot, node and rank: the inverse of {@link #slot(long)},
     * {@link #node(long)} and {@link #rank(long)}.
     *
     * @param slot the slot
     * @param node the node id
     * @param rank the rank inside the slot
     * @return the id
     */
    public static long fromSlot(long slot, int node, int rank) {
        return fromStamp(slotStamp(slot, rank), node);
    }

    /**
     * The time an id was generated.
     *
     * @param id the id
     * @return Unix milliseconds
     */
    public static long epochMillis(long id) {
        return (id >>> TIME_SHIFT) + EPOCH_MILLIS;
    }

    /**
     * The node id of the server that generated an id.
     *
     * @param id the id
     * @return the node id
     */
    public static int node(long id) {
        return (int) ((id >>> SEQUENCE_BITS) & MAX_NODE);
    }

    /**
     * The smallest id of a millisecond: every id generated at or after {@code epochMillis} is at
     * least this.
     *
     * @param epochMillis Unix milliseconds
     * @return the lower bound
     */
    public static long floor(long epochMillis) {
        return timeOffset(epochMillis) << TIME_SHIFT;
    }

    /**
     * The largest id of a millisecond: every id generated at or before {@code epochMillis} is at
     * most this.
     *
     * @param epochMillis Unix milliseconds
     * @return the upper bound
     */
    public static long ceiling(long epochMillis) {
        return (timeOffset(epochMillis) << TIME_SHIFT) | ((1L << TIME_SHIFT) - 1);
    }

    private static long timeOffset(long epochMillis) {
        return Math.clamp(epochMillis - EPOCH_MILLIS, 0L, MAX_TIME_OFFSET);
    }
}
