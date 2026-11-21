package gg.moonrise.hawkeye.entity.importer.source;

import java.util.Map;
import org.jetbrains.annotations.NotNull;

/**
 * A snapshot of a CoreProtect database's dictionaries, keyed by the ids its event rows use.
 *
 * @param users     {@code user.rowid} to user
 * @param worlds    {@code world.id} to world name
 * @param materials {@code material_map.id} to namespaced material ({@code minecraft:stone})
 * @param blockData {@code blockdata_map.id} to one block state token ({@code facing=north})
 * @param entities  {@code entity_map.id} to entity name without namespace ({@code zombie})
 */
public record SourceNames(
        @NotNull Map<Integer, SourceUser> users,
        @NotNull Map<Integer, String> worlds,
        @NotNull Map<Integer, String> materials,
        @NotNull Map<Integer, String> blockData,
        @NotNull Map<Integer, String> entities) {

    /** Copies the maps. */
    public SourceNames {
        users = Map.copyOf(users);
        worlds = Map.copyOf(worlds);
        materials = Map.copyOf(materials);
        blockData = Map.copyOf(blockData);
        entities = Map.copyOf(entities);
    }
}
