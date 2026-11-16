package gg.moonrise.hawkeye.util;

/**
 * The order events are numbered and stored in (docs/architecture.md, "Event id" and "Archive").
 *
 * <p>Within one second a server's events are ranked in two groups. Events that are about a player
 * rather than a place (chat, commands, books, game modes, sessions) come first, in the order they
 * happened, so a conversation reads in order. Every other event follows, ordered by world, then by
 * packed position, then by the order they happened, so the changes to one block keep their order and
 * the events of one place sit together. The archive stores events in the same two groups, which is
 * why it never stores a rank.
 */
public final class EventOrder {

    /** The first action code of the events ordered by time alone. */
    public static final int FIRST_UNPLACED_CODE = 40;

    /** The first action code after the events ordered by time alone. */
    public static final int END_UNPLACED_CODE = 60;

    private EventOrder() {
    }

    /**
     * Whether events of an action are ordered by place. The rule is on the stored code, not on the
     * enum, so it also holds for a code a newer Hawkeye wrote: message and session actions take codes
     * {@value #FIRST_UNPLACED_CODE} to 59, every other action a code outside that range.
     *
     * @param actionCode the stored action code
     * @return {@code true} when the action's events are ordered by world and position
     */
    public static boolean byPlace(int actionCode) {
        return actionCode < FIRST_UNPLACED_CODE || actionCode >= END_UNPLACED_CODE;
    }
}
