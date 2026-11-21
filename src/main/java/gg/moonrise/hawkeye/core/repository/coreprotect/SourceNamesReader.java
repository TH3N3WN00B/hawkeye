package gg.moonrise.hawkeye.core.repository.coreprotect;

import gg.moonrise.hawkeye.entity.importer.SourceNames;
import gg.moonrise.hawkeye.entity.importer.SourceUser;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Reads a CoreProtect database's dictionaries: users by rowid, worlds, materials, block data tokens and
 * entity names by their logical {@code id} (not their rowid). A missing table reads as empty, a row
 * without a name is left out, and a user's UUID is parsed leniently (blank or unreadable is none).
 */
public final class SourceNamesReader {

    private final @NotNull Connection connection;
    private final @NotNull SourceSchema schema;

    /**
     * A reader.
     *
     * @param connection the source connection
     * @param schema     its schema
     */
    public SourceNamesReader(@NotNull Connection connection, @NotNull SourceSchema schema) {
        this.connection = connection;
        this.schema = schema;
    }

    /**
     * Every dictionary.
     *
     * @return the snapshot
     * @throws SQLException when reading fails
     */
    public @NotNull SourceNames read() throws SQLException {
        return new SourceNames(users(), names("world", "world"), names("material_map", "material"),
                names("blockdata_map", "data"), names("entity_map", "entity"));
    }

    /**
     * A UUID from CoreProtect's {@code user.uuid}, leniently.
     *
     * @param text the column, or {@code null}
     * @return the UUID, or empty when blank or not a UUID
     */
    public static @NotNull Optional<UUID> uuid(@Nullable String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(text.strip()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private @NotNull Map<Integer, SourceUser> users() throws SQLException {
        Map<Integer, SourceUser> users = new HashMap<>();
        if (!schema.exists("user")) {
            return users;
        }
        String sql = "SELECT rowid, " + schema.column("user", "user") + ", " + schema.column("user", "uuid")
                + " FROM " + schema.table("user");
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                String name = rows.getString(2);
                if (name != null) {
                    users.put(rows.getInt(1), new SourceUser(name, uuid(rows.getString(3)).orElse(null)));
                }
            }
        }
        return users;
    }

    private @NotNull Map<Integer, String> names(@NotNull String table, @NotNull String column) throws SQLException {
        Map<Integer, String> names = new HashMap<>();
        if (!schema.has(table, "id") || !schema.has(table, column)) {
            return names;
        }
        String sql = "SELECT " + schema.dialect().quote("id") + ", " + schema.dialect().quote(column) + " FROM "
                + schema.table(table);
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                String name = rows.getString(2);
                if (name != null) {
                    names.put(rows.getInt(1), name);
                }
            }
        }
        return names;
    }
}
