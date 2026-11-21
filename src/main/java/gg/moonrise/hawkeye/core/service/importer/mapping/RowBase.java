package gg.moonrise.hawkeye.core.service.importer.mapping;

import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.hawkeye.entity.event.PendingEvent;
import gg.moonrise.hawkeye.entity.payload.Payload;
import gg.moonrise.hawkeye.util.codec.PayloadCodec;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * What every event of one source row shares, resolved once before the row's table decides the rest.
 *
 * @param stamp    the capture stamp at the row's second
 * @param world    the world name
 * @param position the packed position
 * @param actor    who did it
 */
public record RowBase(long stamp, @NotNull String world, long position, @NotNull Actor actor) {

    /**
     * The event of this row.
     *
     * @param action     what happened
     * @param subject    the subject value, or empty
     * @param secondary  the other value, or {@code null}
     * @param amount     the item count, or 0
     * @param payload    the payload, or {@code null}
     * @param rolledBack whether the source had undone it (kept only for reversible actions)
     * @return the mapped row
     */
    public @NotNull MappedRow event(@NotNull Action action, @NotNull String subject, @Nullable String secondary,
            int amount, @Nullable Payload payload, boolean rolledBack) {
        byte[] data = payload == null ? null : PayloadCodec.encode(payload);
        return new MappedRow.Mapped(new PendingEvent(stamp, world, position, actor, action, subject, secondary,
                amount, data, rolledBack && action.reversible()));
    }
}
