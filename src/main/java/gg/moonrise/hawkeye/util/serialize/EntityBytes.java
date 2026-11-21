package gg.moonrise.hawkeye.util.serialize;

import gg.moonrise.hawkeye.util.codec.binary.CodecException;
import gg.moonrise.hawkeye.util.nbt.NbtGzip;
import gg.moonrise.hawkeye.util.nbt.NbtRoot;
import io.papermc.paper.entity.EntitySerializationFlag;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.jetbrains.annotations.NotNull;

/**
 * Paper's version-safe entity serialization (the bytes carry the data version and are upgraded by
 * the server's data fixers when read), stored as raw, normalized NBT ({@link EntitySnapshots}) so the
 * archive's compression finds the repetition between snapshots. Paper exposes it only on
 * {@code UnsafeValues}, whose accessor is deprecated as a whole; this is the one place that calls it.
 */
public final class EntityBytes {

    private EntityBytes() {
    }

    /**
     * Serializes an entity. {@link EntitySerializationFlag#FORCE} is required because Paper refuses an
     * entity that is no longer valid, which a mob in its death event is.
     *
     * @param entity the entity
     * @return its raw, normalized NBT
     * @throws CodecException when Paper's bytes are not NBT
     */
    @SuppressWarnings("deprecation")
    public static byte @NotNull [] serialize(@NotNull Entity entity) {
        byte[] raw = NbtGzip.raw(Bukkit.getUnsafe().serializeEntity(entity, EntitySerializationFlag.FORCE));
        boolean dying = entity instanceof LivingEntity living && (living.isDead() || living.getHealth() <= 0);
        return EntitySnapshots.normalize(NbtRoot.read(raw), entity instanceof Mob, dying).write();
    }

    /**
     * Creates an entity from its bytes without adding it to the world.
     *
     * @param bytes raw NBT from {@link #serialize(Entity)}, or Paper's gzip bytes from earlier builds
     * @param world the world it will be spawned in
     * @return the entity, with a fresh UUID; spawn it with {@code Entity#spawnAt}
     */
    @SuppressWarnings("deprecation")
    public static @NotNull Entity deserialize(byte @NotNull [] bytes, @NotNull World world) {
        return Bukkit.getUnsafe().deserializeEntity(NbtGzip.gzipped(bytes), world, false);
    }
}
