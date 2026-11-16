package gg.moonrise.hawkeye.util;

/**
 * The 64-bit event id: {@code [0][41 bits ms since 2025-01-01Z][10 bits node][12 bits sequence]}.
 * The id is the primary key and the timestamp at once, so a time window is an id range and
 * {@code ORDER BY id DESC} is newest-first across every server (docs/architecture.md, "Event id").
 *
 * <p>Capture produces a <em>stamp</em> ({@code [time][sequence]}, no node) on the game thread; the
 * writer turns it into an id with {@link #fromStamp(long, int)} once the server's node id is known.
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
