package gg.moonrise.hawkeye.entity.event;

import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.hawkeye.entity.payload.Payload;
import gg.moonrise.hawkeye.util.position.BlockPositions;
import gg.moonrise.hawkeye.util.event.EventIds;
import gg.moonrise.hawkeye.util.codec.PayloadCodec;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A written event read back with every dictionary id resolved to its name.
 *
 * @param id         the event id (time, node and sequence)
 * @param server     the name of the server that recorded it
 * @param world      the world name
 * @param position   the packed block position
 * @param actor      who or what caused it
 * @param action     what happened
 * @param subject    the namespaced subject value, or empty
 * @param secondary  the other block state of a block change, or {@code null}
 * @param amount     the item count, or 0
 * @param rolledBack whether a rollback has undone it
 * @param data       the encoded payload, or {@code null}
 */
public record StoredEvent(
        long id,
        @NotNull String server,
        @NotNull String world,
        long position,
        @NotNull Actor actor,
        @NotNull Action action,
        @NotNull String subject,
        @Nullable String secondary,
        int amount,
        boolean rolledBack,
        byte @Nullable [] data) {

    /**
     * When the event happened.
     *
     * @return Unix milliseconds
     */
    public long epochMillis() {
        return EventIds.epochMillis(id);
    }

    /**
     * Block x.
     *
     * @return x
     */
    public int x() {
        return BlockPositions.x(position);
    }

    /**
     * Block y.
     *
     * @return y
     */
    public int y() {
        return BlockPositions.y(position);
    }

    /**
     * Block z.
     *
     * @return z
     */
    public int z() {
        return BlockPositions.z(position);
    }

    /**
     * The decoded payload.
     *
     * @return the payload, or empty when the event has none
     */
    public @NotNull Optional<Payload> payload() {
        return data == null ? Optional.empty() : Optional.of(PayloadCodec.decode(data));
    }

    /**
     * The secondary value.
     *
     * @return the other block state, or empty
     */
    public @NotNull Optional<String> secondaryValue() {
        return Optional.ofNullable(secondary);
    }
}
