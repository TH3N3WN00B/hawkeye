package gg.moonrise.hawkeye.core.service.query;

import gg.moonrise.moss.spring.SpringComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.jetbrains.annotations.NotNull;

/**
 * {@link TypeCatalog} over the server's block, item and entity registries, so {@code i:} accepts
 * exactly the names this Minecraft version knows, including data-pack and modded ones.
 */
@SpringComponent
public final class BukkitTypeCatalog implements TypeCatalog {

    private volatile List<String> names = List.of();

    @Override
    public @NotNull Optional<String> resolve(@NotNull String name) {
        NamespacedKey key = NamespacedKey.fromString(name.toLowerCase(Locale.ROOT));
        if (key == null) {
            return Optional.empty();
        }
        boolean known = Registry.BLOCK.get(key) != null || Registry.ITEM.get(key) != null
                || Registry.ENTITY_TYPE.get(key) != null;
        return known ? Optional.of(key.toString()) : Optional.empty();
    }

    @Override
    public @NotNull List<String> names() {
        List<String> cached = names;
        if (cached.isEmpty()) {
            cached = collect();
            names = cached;
        }
        return cached;
    }

    private static @NotNull List<String> collect() {
        List<String> all = new ArrayList<>();
        addKeys(all, () -> Registry.BLOCK.keyStream().map(NamespacedKey::toString).toList());
        addKeys(all, () -> Registry.ITEM.keyStream().map(NamespacedKey::toString).toList());
        addKeys(all, () -> Registry.ENTITY_TYPE.keyStream().map(NamespacedKey::toString).toList());
        return all.stream().map(key -> key.startsWith("minecraft:") ? key.substring("minecraft:".length()) : key)
                .distinct().sorted().toList();
    }

    private static void addKeys(@NotNull List<String> into, @NotNull Supplier<List<String>> keys) {
        try {
            into.addAll(keys.get());
        } catch (RuntimeException e) {
            // A registry the platform does not provide (MockBukkit) contributes no suggestions.
        }
    }
}
