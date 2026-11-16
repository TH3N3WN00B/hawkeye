package gg.moonrise.hawkeye.core.service.query;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;

/** A {@link TypeCatalog} that knows a handful of vanilla names, for tests without a server. */
public final class FakeTypeCatalog implements TypeCatalog {

    private static final List<String> NAMES = List.of("stone", "diamond_ore", "oak_stairs", "zombie", "chest");

    @Override
    public @NotNull Optional<String> resolve(@NotNull String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        String path = lower.startsWith("minecraft:") ? lower.substring("minecraft:".length()) : lower;
        return NAMES.contains(path) ? Optional.of("minecraft:" + path) : Optional.empty();
    }

    @Override
    public @NotNull List<String> names() {
        return NAMES;
    }
}
