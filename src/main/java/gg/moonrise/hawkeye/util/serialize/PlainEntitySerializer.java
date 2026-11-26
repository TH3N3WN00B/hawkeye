package gg.moonrise.hawkeye.util.serialize;

import gg.moonrise.hawkeye.util.compat.ConditionalOnMissingApi;
import gg.moonrise.hawkeye.util.compat.NewerApi;
import gg.moonrise.moss.spring.SpringComponent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;

/**
 * {@link EntitySerializer} before Paper 1.21.4, which has no serialization flags and does not check that
 * the entity is still valid, so a mob in its death event serializes as it is.
 */
@SpringComponent
@ConditionalOnMissingApi(NewerApi.ENTITY_SERIALIZATION_FLAG)
public final class PlainEntitySerializer implements EntitySerializer {

    @Override
    @SuppressWarnings("deprecation")
    public byte @NotNull [] serialize(@NotNull Entity entity) {
        return Bukkit.getUnsafe().serializeEntity(entity);
    }
}
