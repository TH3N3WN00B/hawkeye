package gg.moonrise.hawkeye.core.repository;

import org.jetbrains.annotations.Nullable;

/**
 * One {@code event} row with dictionary ids. Dictionary ids start at 1, so 0 in {@code secondary} and
 * {@code amount} stands for SQL {@code NULL}.
 *
 * @param id        the event id
 * @param world     the world id
 * @param position  the packed position
 * @param actor     the actor id
 * @param action    the action code
 * @param subject   the subject type id, or 0
 * @param secondary the secondary type id, or 0
 * @param amount    the item count, or 0
 * @param flags     the flag bits ({@link #ROLLED_BACK})
 * @param data      the encoded payload, or {@code null}
 */
public record EventRow(
        long id,
        int world,
        long position,
        int actor,
        int action,
        int subject,
        int secondary,
        int amount,
        int flags,
        byte @Nullable [] data) {

    /** Flag bit: a rollback has undone this event. */
    public static final int ROLLED_BACK = 1;

    /**
     * Whether the rolled-back flag is set.
     *
     * @return {@code true} when rolled back
     */
    public boolean rolledBack() {
        return (flags & ROLLED_BACK) != 0;
    }
}
