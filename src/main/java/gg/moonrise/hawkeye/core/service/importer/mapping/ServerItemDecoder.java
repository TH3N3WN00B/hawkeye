package gg.moonrise.hawkeye.core.service.importer.mapping;

import gg.moonrise.hawkeye.util.ItemBytes;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputFilter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Decodes CoreProtect's item metadata: {@code BukkitObjectOutputStream.writeObject} of a
 * {@code List<List<Map<String, Object>>>} whose first map is {@code ItemMeta#serialize()} and whose
 * further lists hold what CoreProtect took out of it ({@link ItemExtras}). The stream only resolves
 * {@code java.lang}, {@code java.util}, Guava's immutable collections and {@code org.bukkit} classes
 * ({@link #FILTER}), so a blob cannot make the server load or construct anything else; a blob over
 * {@link #MAX_BYTES} is not read. Every failure is logged at DEBUG and counted, never thrown.
 */
@Slf4j
public final class ServerItemDecoder implements ItemDecoder {

    /** The largest blob decoded. */
    public static final int MAX_BYTES = 4 * 1024 * 1024;

    /** What the object stream may resolve, and how deep, many and large it may get. */
    public static final ObjectInputFilter FILTER = ObjectInputFilter.Config.createFilter("maxdepth=32;"
            + "maxrefs=100000;maxarray=100000;maxbytes=" + MAX_BYTES + ";java.lang.*;java.util.*;"
            + "com.google.common.collect.*;org.bukkit.**;!*");

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
            List<List<Map<String, Object>>> lists = lists(read(metadata));
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

    @SuppressWarnings("deprecation")
    private static @NotNull Object read(byte @NotNull [] metadata) throws IOException, ClassNotFoundException {
        try (BukkitObjectInputStream in = new BukkitObjectInputStream(new ByteArrayInputStream(metadata))) {
            in.setObjectInputFilter(FILTER);
            return in.readObject();
        }
    }

    /** Checks the shape CoreProtect writes and copies it with types the rest can rely on. */
    private static @NotNull List<List<Map<String, Object>>> lists(@NotNull Object root) {
        if (!(root instanceof List<?> outer)) {
            throw new IllegalArgumentException("not a list but " + root.getClass().getSimpleName());
        }
        List<List<Map<String, Object>>> lists = new ArrayList<>(outer.size());
        for (Object inner : outer) {
            if (!(inner instanceof List<?> maps)) {
                throw new IllegalArgumentException("an entry is not a list (an older CoreProtect format)");
            }
            List<Map<String, Object>> list = new ArrayList<>(maps.size());
            for (Object map : maps) {
                list.add(ItemExtras.stringKeys(map));
            }
            lists.add(list);
        }
        return lists;
    }

    private <T> @NotNull Optional<T> failed(@NotNull String material, @NotNull String reason,
            @Nullable Throwable error) {
        failures.incrementAndGet();
        log.debug("Could not decode the CoreProtect metadata of a {} item: {}", material, reason, error);
        return Optional.empty();
    }
}
