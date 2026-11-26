package gg.moonrise.hawkeye.util.serialize;

import gg.moonrise.hawkeye.util.compat.ConditionalOnApi;
import gg.moonrise.hawkeye.util.compat.NewerApi;
import gg.moonrise.moss.spring.SpringComponent;
import io.papermc.paper.entity.EntitySerializationFlag;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;

/**
 * {@link EntitySerializer} on Paper 1.21.4 and later. {@link EntitySerializationFlag#FORCE} is required
 * because Paper refuses an entity that is no longer valid, which a mob in its death event is.
 */
@SpringComponent
@ConditionalOnApi(NewerApi.ENTITY_SERIALIZATION_FLAG)
public final class ForcedEntitySerializer implements EntitySerializer {

    @Override
    @SuppressWarnings("deprecation")
    public byte @NotNull [] serialize(@NotNull Entity entity) {
        return Bukkit.getUnsafe().serializeEntity(entity, EntitySerializationFlag.FORCE);
    }
}
