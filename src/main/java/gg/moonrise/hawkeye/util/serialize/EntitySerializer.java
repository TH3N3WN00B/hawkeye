package gg.moonrise.hawkeye.util.serialize;

import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;

/**
 * Paper's entity serialization, whose call changed in 1.21.4: from then on an entity that is no longer
 * valid, as a mob in its death event is, is serialized only when forced. One implementation is registered:
 * {@code ForcedEntitySerializer} where the server has the flags, {@link PlainEntitySerializer} before.
 */
public interface EntitySerializer {

    /**
     * Paper's bytes for an entity, including one in its death event.
     *
     * @param entity the entity
     * @return gzip NBT carrying the data version
     */
    byte @NotNull [] serialize(@NotNull Entity entity);
}
