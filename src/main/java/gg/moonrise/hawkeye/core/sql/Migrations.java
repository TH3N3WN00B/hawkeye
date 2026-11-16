package gg.moonrise.hawkeye.core.sql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

/**
 * Brings a shard's schema to {@link Schema#CURRENT}. The version lives in a one-row
 * {@code schema_version} table; each version's statements are idempotent, so concurrent first starts
 * of several servers against one database are safe.
 */
@Slf4j
public final class Migrations {

    /** The version that replaced the development archive format. */
    public static final int ARCHIVE_FORMAT_2 = 3;

    private Migrations() {
    }

    /**
     * Reads the schema version and applies every missing version.
     *
     * @param connection a connection in auto-commit mode
     * @param dialect    the engine
     * @param tables     the table names
     * @return what happened
     * @throws SQLException when a statement fails
     */
    public static @NotNull MigrationResult apply(@NotNull Connection connection, @NotNull Dialect dialect,
            @NotNull Tables tables) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS " + tables.schemaVersion()
                    + " (id INTEGER NOT NULL PRIMARY KEY, version INTEGER NOT NULL)");
        }
        int version = version(connection, tables);
        if (version > Schema.CURRENT) {
            return new MigrationResult.DatabaseAhead(version);
        }
        if (version == Schema.CURRENT) {
            return new MigrationResult.UpToDate(version);
        }
        if (version > 0 && version < ARCHIVE_FORMAT_2) {
            log.warn("Schema version {} -> {}: archive segments written by development builds are discarded "
                    + "(the archive format changed before the first release); events still in the hot table are "
                    + "kept", version, Schema.CURRENT);
        }
        for (int next = version + 1; next <= Schema.CURRENT; next++) {
            try (Statement statement = connection.createStatement()) {
                for (String sql : Schema.statements(next, dialect, tables)) {
                    statement.execute(sql);
                }
            }
            setVersion(connection, dialect, tables, next);
        }
        return new MigrationResult.Migrated(version, Schema.CURRENT);
    }

    /**
     * The stored schema version.
     *
     * @param connection a connection
     * @param tables     the table names
     * @return the version, 0 when the database is fresh
     * @throws SQLException when the query fails
     */
    public static int version(@NotNull Connection connection, @NotNull Tables tables) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT version FROM " + tables.schemaVersion() + " WHERE id = 1");
             ResultSet rows = statement.executeQuery()) {
            return rows.next() ? rows.getInt(1) : 0;
        }
    }

    private static void setVersion(@NotNull Connection connection, @NotNull Dialect dialect, @NotNull Tables tables,
            int version) throws SQLException {
        String sql = dialect.upsert(tables.schemaVersion(), List.of("id", "version"), "id",
                List.of("version"), 1);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, 1);
            statement.setInt(2, version);
            statement.executeUpdate();
        }
    }
}
