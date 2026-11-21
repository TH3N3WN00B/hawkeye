package gg.moonrise.hawkeye.entity.event;

import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.hawkeye.util.event.EventIds;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * An event captured on the game thread and not yet written. It holds names, not dictionary ids, and
 * a capture stamp, not an id: the writer interns the names and adds the node id
 * ({@link EventIds#fromStamp(long, int)}), so capturing never waits for the database.
 *
 * @param stamp      the capture stamp from {@code core.time.EventClock}
 * @param world      the world name
 * @param position   the packed block position ({@code util.position.BlockPositions})
 * @param actor      who or what caused it
 * @param action     what happened
 * @param subject    the namespaced subject value (block state, material or entity type), or empty
 * @param secondary  the other block state of a block change, or {@code null}
 * @param amount     the item count, or 0
 * @param data       the encoded payload ({@code util.codec.PayloadCodec}), or {@code null}
 * @param rolledBack stored as already rolled back: imported history whose source had undone it
 */
public record PendingEvent(
        long stamp,
        @NotNull String world,
        long position,
        @NotNull Actor actor,
        @NotNull Action action,
        @NotNull String subject,
        @Nullable String secondary,
        int amount,
        byte @Nullable [] data,
        boolean rolledBack) {

    /**
     * A captured event, not rolled back: what capture and the spool create.
     *
     * @param stamp     the capture stamp
     * @param world     the world name
     * @param position  the packed block position
     * @param actor     who or what caused it
     * @param action    what happened
     * @param subject   the namespaced subject value, or empty
     * @param secondary the other block state of a block change, or {@code null}
     * @param amount    the item count, or 0
     * @param data      the encoded payload, or {@code null}
     */
    public PendingEvent(long stamp, @NotNull String world, long position, @NotNull Actor actor,
            @NotNull Action action, @NotNull String subject, @Nullable String secondary, int amount,
            byte @Nullable [] data) {
        this(stamp, world, position, actor, action, subject, secondary, amount, data, false);
    }

    /**
     * This event with another stamp (the writer's final one), everything else kept.
     *
     * @param stamp the new stamp
     * @return the copy
     */
    public @NotNull PendingEvent withStamp(long stamp) {
        return new PendingEvent(stamp, world, position, actor, action, subject, secondary, amount, data, rolledBack);
    }

    /**
     * When the event was captured.
     *
     * @return Unix milliseconds
     */
    public long epochMillis() {
        return EventIds.stampMillis(stamp);
    }
}
