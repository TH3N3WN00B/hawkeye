package gg.moonrise.hawkeye.core.service.query;

import java.util.List;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;

/** Which block, item and entity type names exist, for parsing and suggesting {@code i:} and {@code e:}. */
public interface TypeCatalog {

    /**
     * Normalizes a type name.
     *
     * @param name e.g. {@code stone}, {@code minecraft:stone} or {@code ZOMBIE}
     * @return the namespaced key ({@code minecraft:stone}), or empty when no such type exists
     */
    @NotNull Optional<String> resolve(@NotNull String name);

    /**
     * Every type name without its {@code minecraft:} namespace, for suggestions.
     *
     * @return the names
     */
    @NotNull List<String> names();
}
