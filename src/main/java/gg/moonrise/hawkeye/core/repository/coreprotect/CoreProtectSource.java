package gg.moonrise.hawkeye.core.repository.coreprotect;

import gg.moonrise.hawkeye.entity.importer.SourceCursor;
import gg.moonrise.hawkeye.entity.importer.SourceNames;
import gg.moonrise.hawkeye.entity.importer.SourceRow;
import gg.moonrise.hawkeye.entity.importer.SourceSettings;
import gg.moonrise.hawkeye.entity.importer.SourceTable;
import gg.moonrise.hawkeye.entity.importer.SourceType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

/**
 * One open CoreProtect database: a single read-only JDBC connection (not a pool) and the readers over
 * it. Each table is walked in the engine's cheap physical order ({@link #order()}): rowid windows on
 * SQLite, MySQL and DuckDB ({@link RowidScan}), {@code (time, rowid)} keyset pages on ClickHouse
 * ({@link TimeScan}). Columns and optional tables an older CoreProtect lacks read as NULL or as absent.
 * Used by one thread at a time; close it when done.
 */
@Slf4j
public final class CoreProtectSource implements AutoCloseable {

    /** CoreProtect's {@code database_lock.status} while a migration is incomplete. */
    public static final int MIGRATING = 2;

    private final @NotNull SourceSettings settings;
    private final @NotNull Connection connection;
    private final @NotNull SourceSchema schema;
    private final @NotNull TableScan scan;
    private final @NotNull SpawnUuids spawns;
    private final @NotNull SourceNamesReader names;

    private CoreProtectSource(@NotNull SourceSettings settings, @NotNull Connection connection) {
        SourceDialect dialect = SourceDialect.of(settings.type());
        this.settings = settings;
        this.connection = connection;
        this.schema = new SourceSchema(connection, settings, dialect);
        SourceRowMapper mapper = new SourceRowMapper(schema);
        this.scan = switch (dialect.order()) {
            case ROWID -> new RowidScan(connection, schema, mapper);
            case TIME -> new TimeScan(connection, schema, mapper);
        };
        this.spawns = new SpawnUuids(connection, schema);
        this.names = new SourceNamesReader(connection, schema);
    }

    /**
     * Opens a source read-only. A file engine always reads the {@code co_} tables, whatever prefix the
     * settings carry.
     *
     * @param settings the source
     * @param driver   the engine's driver ({@link SourceDrivers})
     * @return the open source
     * @throws SQLException when the prefix is invalid or the connection cannot be opened
     */
    public static @NotNull CoreProtectSource open(@NotNull SourceSettings settings, @NotNull Driver driver)
            throws SQLException {
        SourceSettings effective = fileEngine(settings.type()) && !settings.prefix().equals(SourceSettings.DEFAULT_PREFIX)
                ? SourceSettings.ofFile(settings.type(), settings.file()) : settings;
        if (!SourceDialect.validPrefix(effective.prefix())) {
            throw new SQLException("invalid CoreProtect table prefix \"" + effective.prefix() + "\"");
        }
        SourceDialect dialect = SourceDialect.of(effective.type());
        Connection connection = driver.connect(dialect.url(effective), dialect.properties(effective));
        if (connection == null) {
            throw new SQLException("the " + effective.type() + " driver does not accept " + effective.describe());
        }
        try {
            dialect.prepare(connection);
        } catch (SQLException | RuntimeException e) {
            closeQuietly(connection, e);
            throw e;
        }
        return new CoreProtectSource(effective, connection);
    }

    /**
     * Where the source is.
     *
     * @return the settings it was opened with
     */
    public @NotNull SourceSettings settings() {
        return settings;
    }

    /**
     * The scan order, as an import's checkpoint stores it.
     *
     * @return {@code rowid} (SQLite, MySQL, DuckDB) or {@code time} (ClickHouse)
     */
    public @NotNull String order() {
        return SourceDialect.of(settings.type()).order().key();
    }

    /**
     * What identifies this CoreProtect database wherever it is reached from: the time and version of its
     * first {@code version} row (written when CoreProtect created it). Without one, a hash of the
     * location.
     *
     * @return {@code "<time>:<version>"}, or {@code "location-<hex>"}
     * @throws SQLException when reading fails
     */
    public @NotNull String fingerprint() throws SQLException {
        if (schema.has("version", "time") && schema.has("version", "version")) {
            String sql = "SELECT " + schema.dialect().quote("time") + ", " + schema.dialect().quote("version")
                    + " FROM " + schema.table("version") + " ORDER BY rowid LIMIT 1";
            try (PreparedStatement statement = connection.prepareStatement(sql);
                    ResultSet rows = statement.executeQuery()) {
                if (rows.next()) {
                    return rows.getLong(1) + ":" + rows.getString(2);
                }
            }
        }
        return "location-" + hash(settings.describe());
    }

    /**
     * Whether a CoreProtect migration of this database is incomplete ({@code database_lock.status} 2):
     * its rows must not be imported then.
     *
     * @return {@code true} while migrating
     * @throws SQLException when reading fails
     */
    public boolean migrating() throws SQLException {
        if (!schema.has("database_lock", "status")) {
            return false;
        }
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM "
                + schema.table("database_lock") + " WHERE " + schema.dialect().quote("status") + " = ?")) {
            statement.setInt(1, MIGRATING);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() && rows.getLong(1) > 0;
            }
        }
    }

    /**
     * The event tables this database has.
     *
     * @return the tables, in merge order
     * @throws SQLException when the connection fails
     */
    public @NotNull Set<SourceTable> tables() throws SQLException {
        Set<SourceTable> tables = EnumSet.noneOf(SourceTable.class);
        for (SourceTable table : SourceTable.values()) {
            if (schema.exists(table.tableName())) {
                tables.add(table);
            }
        }
        return tables;
    }

    /**
     * A snapshot of the dictionaries.
     *
     * @return users, worlds, materials, block data tokens and entity names
     * @throws SQLException when reading fails
     */
    public @NotNull SourceNames names() throws SQLException {
        return names.read();
    }

    /**
     * The next rows after a cursor, in scan order. A spawn row ({@code block} action 13) carries its
     * entity's UUID when {@code entity_spawn} knows it.
     *
     * @param table a table from {@link #tables()}
     * @param after the last row consumed
     * @param limit the most rows
     * @return at most {@code limit} rows; empty when nothing more follows right now
     * @throws SQLException when reading fails
     */
    public @NotNull List<SourceRow> read(@NotNull SourceTable table, @NotNull SourceCursor after, int limit)
            throws SQLException {
        List<SourceRow> rows = scan.read(table, after, limit);
        return table == SourceTable.BLOCK ? spawns.resolve(rows) : rows;
    }

    /**
     * About how many rows follow a cursor, for progress: {@code MAX(rowid)} minus the cursor in rowid
     * order (never a full count), a count beyond the cursor on ClickHouse.
     *
     * @param table a table from {@link #tables()}
     * @param after the last row consumed
     * @return the estimate, never negative
     * @throws SQLException when reading fails
     */
    public long remaining(@NotNull SourceTable table, @NotNull SourceCursor after) throws SQLException {
        return scan.remaining(table, after);
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            log.warn("Could not close the CoreProtect connection to {}", settings.describe(), e);
        }
    }

    private static boolean fileEngine(@NotNull SourceType type) {
        return type == SourceType.SQLITE || type == SourceType.DUCKDB;
    }

    private static @NotNull String hash(@NotNull String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java runtime has SHA-256", e);
        }
    }

    private static void closeQuietly(@NotNull Connection connection, @NotNull Exception cause) {
        try {
            connection.close();
        } catch (SQLException close) {
            cause.addSuppressed(close);
        }
    }
}
