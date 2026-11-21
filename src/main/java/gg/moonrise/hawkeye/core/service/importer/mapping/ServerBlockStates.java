package gg.moonrise.hawkeye.core.service.importer.mapping;

import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

/**
 * Normalises block states through the server ({@code Bukkit.createBlockData(state).getAsString()}),
 * caching every distinct string. A string the server rejects is kept as it is. Used by one import
 * thread; the server's block data parser only reads registries.
 */
public final class ServerBlockStates implements BlockStates {

    /** The most distinct states kept; the least recently used goes first. */
    public static final int CACHE_SIZE = 65_536;

    private final Map<String, String> cache = new LinkedHashMap<>(1024, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    @Override
    public @NotNull String normalize(@NotNull String state) {
        String cached = cache.get(state);
        if (cached != null) {
            return cached;
        }
        String normalized = parse(state);
        cache.put(state, normalized);
        return normalized;
    }

    private static @NotNull String parse(@NotNull String state) {
        try {
            return Bukkit.createBlockData(state).getAsString();
        } catch (RuntimeException e) {
            return state;
        }
    }
}
