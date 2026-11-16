package gg.moonrise.hawkeye.util;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;

/**
 * Paper's version-safe entity serialization (the bytes carry the data version and are upgraded by
 * the server's data fixers when read). Paper exposes it only on {@code UnsafeValues}, whose accessor
 * is deprecated as a whole; this is the one place that calls it.
 */
public final class EntityBytes {

    private EntityBytes() {
    }

    /**
     * Serializes an entity.
     *
     * @param entity the entity
     * @return Paper's entity bytes
     */
    @SuppressWarnings("deprecation")
    public static byte @NotNull [] serialize(@NotNull Entity entity) {
        return Bukkit.getUnsafe().serializeEntity(entity);
    }

    /**
     * Creates an entity from its bytes without adding it to the world.
     *
     * @param bytes Paper's entity bytes
     * @param world the world it will be spawned in
     * @return the entity, with a fresh UUID; spawn it with {@code Entity#spawnAt}
     */
    @SuppressWarnings("deprecation")
    public static @NotNull Entity deserialize(byte @NotNull [] bytes, @NotNull World world) {
        return Bukkit.getUnsafe().deserializeEntity(bytes, world, false);
    }
}
