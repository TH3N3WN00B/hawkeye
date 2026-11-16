package gg.moonrise.hawkeye.core.repository;

import gg.moonrise.hawkeye.core.sql.Dialect;
import gg.moonrise.hawkeye.core.sql.Sql;
import gg.moonrise.hawkeye.entity.server.WorldKey;
import gg.moonrise.moss.spring.SpringComponent;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.NotNull;

/**
 * The {@code type} and {@code world} dictionaries. Interning is insert-or-ignore followed by a select,
 * so any number of servers can intern the same value at once and all get the same id. Rows are never
 * changed or deleted, which is what lets every server cache them forever.
 */
@SpringComponent
public final class DictionaryRepository {

    /** Values per statement. */
    public static final int VALUES_PER_STATEMENT = 500;

    /**
     * The ids of type names, creating the missing ones. Existing names are selected first and only the
     * missing ones are inserted: PostgreSQL identities and InnoDB auto-increments consume an id even
     * for an insert that conflicts.
     *
     * @param connection a connection in auto-commit mode (dictionary rows must outlive a failed batch)
     * @param sql        the shard's SQL context
     * @param names      namespaced names
     * @return name to id, for every name
     * @throws SQLException when a statement fails
     */
    public @NotNull Map<String, Integer> internTypes(@NotNull Connection connection, @NotNull Sql sql,
            @NotNull Collection<String> names) throws SQLException {
        Map<String, Integer> ids = typeIds(connection, sql, new HashSet<>(names));
        List<String> missing = names.stream().distinct().filter(name -> !ids.containsKey(name)).toList();
        for (int from = 0; from < missing.size(); from += VALUES_PER_STATEMENT) {
            List<String> chunk = missing.subList(from, Math.min(missing.size(), from + VALUES_PER_STATEMENT));
            String insert = sql.dialect().insertIgnoringDuplicates(sql.tables().type(), List.of("name"), chunk.size());
            try (PreparedStatement statement = connection.prepareStatement(insert)) {
                for (int i = 0; i < chunk.size(); i++) {
                    statement.setString(i + 1, chunk.get(i));
                }
                statement.executeUpdate();
            }
        }
        if (!missing.isEmpty()) {
            ids.putAll(typeIds(connection, sql, missing));
        }
        return ids;
    }

    /**
     * The ids of existing type names.
     *
     * @param connection a connection
     * @param sql        the shard's SQL context
     * @param names      namespaced names
     * @return name to id, for the names that exist
     * @throws SQLException when a query fails
     */
    public @NotNull Map<String, Integer> typeIds(@NotNull Connection connection, @NotNull Sql sql,
            @NotNull Collection<String> names) throws SQLException {
        Map<String, Integer> ids = new HashMap<>();
        List<String> values = List.copyOf(names);
        for (int from = 0; from < values.size(); from += VALUES_PER_STATEMENT) {
            List<String> chunk = values.subList(from, Math.min(values.size(), from + VALUES_PER_STATEMENT));
            String select = "SELECT name, id FROM " + sql.tables().type() + " WHERE name IN ("
                    + Dialect.placeholders(chunk.size()) + ")";
            try (PreparedStatement statement = connection.prepareStatement(select)) {
                for (int i = 0; i < chunk.size(); i++) {
                    statement.setString(i + 1, chunk.get(i));
                }
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        ids.put(rows.getString(1), rows.getInt(2));
                    }
                }
            }
        }
        return ids;
    }

    /**
     * The names of type ids.
     *
     * @param connection a connection
     * @param sql        the shard's SQL context
     * @param ids        type ids
     * @return id to name, for the ids that exist
     * @throws SQLException when a query fails
     */
    public @NotNull Map<Integer, String> typeNames(@NotNull Connection connection, @NotNull Sql sql,
            @NotNull Collection<Integer> ids) throws SQLException {
        Map<Integer, String> names = new HashMap<>();
        List<Integer> values = List.copyOf(new HashSet<>(ids));
        for (int from = 0; from < values.size(); from += VALUES_PER_STATEMENT) {
            List<Integer> chunk = values.subList(from, Math.min(values.size(), from + VALUES_PER_STATEMENT));
            String select = "SELECT id, name FROM " + sql.tables().type() + " WHERE id IN ("
                    + Dialect.placeholders(chunk.size()) + ")";
            try (PreparedStatement statement = connection.prepareStatement(select)) {
                for (int i = 0; i < chunk.size(); i++) {
                    statement.setInt(i + 1, chunk.get(i));
                }
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        names.put(rows.getInt(1), rows.getString(2));
                    }
                }
            }
        }
        return names;
    }

    /**
     * Every type id of a material or entity type: the plain name and every block state of it
     * ({@code minecraft:oak_stairs} matches {@code minecraft:oak_stairs[facing=east,...]}).
     *
     * @param connection a connection
     * @param sql        the shard's SQL context
     * @param key        a namespaced key without properties
     * @return the matching ids
     * @throws SQLException when the query fails
     */
    public @NotNull Set<Integer> typeIdsOf(@NotNull Connection connection, @NotNull Sql sql, @NotNull String key)
            throws SQLException {
        Set<Integer> ids = new HashSet<>();
        String select = "SELECT id FROM " + sql.tables().type() + " WHERE name = ? OR name LIKE ? ESCAPE '!'";
        try (PreparedStatement statement = connection.prepareStatement(select)) {
            statement.setString(1, key);
            statement.setString(2, key.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "[%");
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    ids.add(rows.getInt(1));
                }
            }
        }
        return ids;
    }

    /**
     * The ids of worlds in a scope, creating the missing ones.
     *
     * @param connection a connection in auto-commit mode
     * @param sql        the shard's SQL context
     * @param scope      the world scope
     * @param names      world names
     * @return world name to id, for every name
     * @throws SQLException when a statement fails
     */
    public @NotNull Map<String, Integer> internWorlds(@NotNull Connection connection, @NotNull Sql sql,
            @NotNull String scope, @NotNull Collection<String> names) throws SQLException {
        Map<String, Integer> ids = worldIds(connection, sql, scope);
        List<String> missing = names.stream().distinct().filter(name -> !ids.containsKey(name)).toList();
        if (missing.isEmpty()) {
            return ids;
        }
        String insert = sql.dialect().insertIgnoringDuplicates(sql.tables().world(), List.of("scope", "name"),
                missing.size());
        try (PreparedStatement statement = connection.prepareStatement(insert)) {
            int index = 1;
            for (String name : missing) {
                statement.setString(index++, scope);
                statement.setString(index++, name);
            }
            statement.executeUpdate();
        }
        return worldIds(connection, sql, scope);
    }

    private @NotNull Map<String, Integer> worldIds(@NotNull Connection connection, @NotNull Sql sql,
            @NotNull String scope) throws SQLException {
        Map<String, Integer> ids = new HashMap<>();
        worlds(connection, sql, Set.of(scope)).forEach((id, key) -> ids.put(key.name(), id));
        return ids;
    }

    /**
     * Every world of some scopes.
     *
     * @param connection a connection
     * @param sql        the shard's SQL context
     * @param scopes     world scopes, or empty for every world of the shard
     * @return world id to key
     * @throws SQLException when the query fails
     */
    public @NotNull Map<Integer, WorldKey> worlds(@NotNull Connection connection, @NotNull Sql sql,
            @NotNull Collection<String> scopes) throws SQLException {
        List<String> values = new ArrayList<>(new HashSet<>(scopes));
        String select = "SELECT id, scope, name FROM " + sql.tables().world()
                + (values.isEmpty() ? "" : " WHERE scope IN (" + Dialect.placeholders(values.size()) + ")");
        Map<Integer, WorldKey> worlds = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(select)) {
            for (int i = 0; i < values.size(); i++) {
                statement.setString(i + 1, values.get(i));
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    worlds.put(rows.getInt(1), new WorldKey(rows.getString(2), rows.getString(3)));
                }
            }
        }
        return worlds;
    }
}
