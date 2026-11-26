package gg.moonrise.hawkeye.core.service.importer.mapping.item;

import gg.moonrise.hawkeye.util.serialize.ItemBytes;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputFilter.FilterInfo;
import java.io.ObjectInputFilter.Status;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.Material;
import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Decodes CoreProtect's item metadata: {@code BukkitObjectOutputStream.writeObject} of a
 * {@code List<List<Map<String, Object>>>} whose first map is {@code ItemMeta#serialize()} and whose
 * further lists hold what CoreProtect took out of it ({@link ItemExtras}). The stream's own objects may only
 * be {@code java.lang}, {@code java.util}, Guava's immutable collections and {@code org.bukkit} classes
 * ({@link #FILTER}), so a blob cannot make the server load or construct anything else; a blob over
 * {@link #MAX_BYTES} is not read. Every failure is logged at DEBUG and counted, never thrown.
 */
@Slf4j
public final class ServerItemDecoder implements ItemDecoder {

    /** The largest blob decoded. */
    public static final int MAX_BYTES = 4 * 1024 * 1024;

    /** How deep, many and large the object stream may get. */
    private static final ObjectInputFilter LIMITS = ObjectInputFilter.Config.createFilter("maxdepth=32;"
            + "maxrefs=100000;maxarray=100000;maxbytes=" + MAX_BYTES);

    /** The classes the stream's own objects may have. */
    private static final ObjectInputFilter CLASSES = ObjectInputFilter.Config.createFilter("java.lang.*;"
            + "java.util.*;com.google.common.collect.*;org.bukkit.**;!*");

    /**
     * What the object stream may resolve: {@link #LIMITS} and {@link #CLASSES}, and the objects
     * {@code BukkitObjectInputStream} rebuilds from a wrapped map with {@code ConfigurationSerialization},
     * which the JDK checks as well and which may be the server's own classes (a head's {@code skull-owner} is
     * Paper's {@code com.destroystokyo.paper.profile.CraftPlayerProfile}). Those are let through by kind: a
     * class that is {@code ConfigurationSerializable} but not {@code Serializable}, which the stream cannot
     * construct itself; only a class the server registered for configuration serialization is ever built.
     */
    public static final ObjectInputFilter FILTER = ServerItemDecoder::check;

    private final AtomicLong failures = new AtomicLong();

    @Override
    public @NotNull Optional<byte[]> decode(@NotNull String material, byte @NotNull [] metadata) {
        Optional<ItemStack> item = rebuild(material, metadata);
        if (item.isEmpty() || item.get().isSimilar(new ItemStack(item.get().getType()))) {
            return Optional.empty();
        }
        try {
            return Optional.of(ItemBytes.serialize(item.get()));
        } catch (RuntimeException e) {
            return failed(material, e.toString(), e);
        }
    }

    /**
     * Rebuilds the item a blob describes, as one item of the material. Never throws.
     *
     * @param material the item's namespaced material
     * @param metadata the blob
     * @return the item (with the server's default components when the blob adds none), or empty when the
     *         blob cannot be decoded
     */
    public @NotNull Optional<ItemStack> rebuild(@NotNull String material, byte @NotNull [] metadata) {
        if (metadata.length > MAX_BYTES) {
            return failed(material, "a blob of " + metadata.length + " bytes is too large", null);
        }
        Material type = Material.matchMaterial(material);
        if (type == null || !type.isItem() || type.isAir()) {
            return failed(material, "the server has no such item", null);
        }
        try {
            List<List<Map<String, Object>>> lists = ItemExtras.lists(read(metadata));
            ItemStack item = new ItemStack(type);
            ItemExtras.apply(item, lists);
            return Optional.of(item);
        } catch (IOException | ClassNotFoundException | RuntimeException | LinkageError e) {
            return failed(material, e.toString(), e);
        }
    }

    @Override
    public long failures() {
        return failures.get();
    }

    private static @NotNull Status check(@NotNull FilterInfo info) {
        if (LIMITS.checkInput(info) == Status.REJECTED) {
            return Status.REJECTED;
        }
        Class<?> type = info.serialClass();
        if (type != null && !type.isArray() && ConfigurationSerializable.class.isAssignableFrom(type)
                && !Serializable.class.isAssignableFrom(type)) {
            return Status.ALLOWED;
        }
        return CLASSES.checkInput(info);
    }

    @SuppressWarnings("deprecation")
    private static @NotNull Object read(byte @NotNull [] metadata) throws IOException, ClassNotFoundException {
        try (BukkitObjectInputStream in = new BukkitObjectInputStream(new ByteArrayInputStream(metadata))) {
            in.setObjectInputFilter(FILTER);
            return in.readObject();
        }
    }

    private <T> @NotNull Optional<T> failed(@NotNull String material, @NotNull String reason,
            @Nullable Throwable error) {
        failures.incrementAndGet();
        log.debug("Could not decode the CoreProtect metadata of a {} item: {}", material, reason, error);
        return Optional.empty();
    }
}
