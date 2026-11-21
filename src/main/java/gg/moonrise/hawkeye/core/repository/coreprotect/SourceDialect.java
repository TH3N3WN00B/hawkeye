package gg.moonrise.hawkeye.core.repository.coreprotect;

import gg.moonrise.hawkeye.entity.importer.SourceSettings;
import gg.moonrise.hawkeye.entity.importer.SourceType;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.sqlite.SQLiteConfig;

/**
 * How each CoreProtect engine is spoken to: identifier quoting, the JDBC URL and properties, the scan
 * order and how a blob column reads. Every connection is opened read-only where the engine allows it
 * (SQLite opens the file read-only, MySQL marks the session read-only, DuckDB opens the file read-only);
 * ClickHouse is only ever sent {@code SELECT}s.
 */
public enum SourceDialect {
    SQLITE('"', ScanOrder.ROWID),
    MYSQL('`', ScanOrder.ROWID),
    DUCKDB('"', ScanOrder.ROWID),
    CLICKHOUSE('`', ScanOrder.TIME);

    /** A CoreProtect {@code table-prefix}: what CoreProtect itself accepts. */
    private static final Pattern PREFIX = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /** A host name or IP literal, IPv6 bare or in brackets: nothing that could add to a JDBC URL. */
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9.:-]+|\\[[A-Za-z0-9.:-]+]");

    /** A database name as it may stand in a JDBC URL's path. */
    private static final Pattern DATABASE = Pattern.compile("[A-Za-z0-9_$-]+");

    private final char quote;
    private final ScanOrder order;

    SourceDialect(char quote, @NotNull ScanOrder order) {
        this.quote = quote;
        this.order = order;
    }

    /**
     * The dialect of an engine.
     *
     * @param type the engine
     * @return its dialect
     */
    public static @NotNull SourceDialect of(@NotNull SourceType type) {
        return switch (type) {
            case SQLITE -> SQLITE;
            case MYSQL -> MYSQL;
            case DUCKDB -> DUCKDB;
            case CLICKHOUSE -> CLICKHOUSE;
        };
    }

    /**
     * Whether a table prefix is one CoreProtect accepts, so it can be put into SQL as it is.
     *
     * @param prefix the prefix
     * @return {@code true} when it matches {@code [A-Za-z_][A-Za-z0-9_]*}
     */
    public static boolean validPrefix(@NotNull String prefix) {
        return PREFIX.matcher(prefix).matches();
    }

    /**
     * Whether a server engine's host can be put into a JDBC URL as it is.
     *
     * @param host the host from CoreProtect's config
     * @return {@code true} for a host name or an IP literal (letters, digits, {@code .}, {@code -},
     *         {@code :}, and {@code []} around an IPv6 address)
     */
    public static boolean validHost(@NotNull String host) {
        return HOST.matcher(host).matches();
    }

    /**
     * Whether a server engine's database name can be put into a JDBC URL as it is.
     *
     * @param database the database from CoreProtect's config
     * @return {@code true} when it is not empty and holds only letters, digits, {@code _}, {@code -} and {@code $}
     */
    public static boolean validDatabase(@NotNull String database) {
        return DATABASE.matcher(database).matches();
    }

    /**
     * How the engine's tables are walked.
     *
     * @return the scan order
     */
    public @NotNull ScanOrder order() {
        return order;
    }

    /**
     * A quoted identifier. Only validated names are passed (prefix plus a fixed table or column name),
     * so the quote character never occurs inside.
     *
     * @param identifier the name
     * @return the quoted name
     * @throws IllegalArgumentException when the name is not a plain identifier
     */
    public @NotNull String quote(@NotNull String identifier) {
        if (!validPrefix(identifier)) {
            throw new IllegalArgumentException("not a plain identifier: " + identifier);
        }
        return quote + identifier + quote;
    }

    /**
     * A source table's quoted name.
     *
     * @param settings the source
     * @param name     the table name without the prefix
     * @return the quoted, prefixed name
     */
    public @NotNull String table(@NotNull SourceSettings settings, @NotNull String name) {
        return quote(settings.prefix() + name);
    }

    /**
     * The catalog query that tells whether a table (or a view: ClickHouse's CoreProtect tables are views)
     * exists where the source's unqualified names resolve: {@code sqlite_master} on SQLite,
     * {@code information_schema.tables} of the connection's current database on MySQL and ClickHouse and
     * of the {@code main} schema on DuckDB. Its one parameter is the prefixed table name; it returns a
     * row when the table exists.
     *
     * @return the query
     */
    public @NotNull String tableExists() {
        return switch (this) {
            case SQLITE -> "SELECT 1 FROM sqlite_master WHERE type IN ('table', 'view') AND name = ?";
            case MYSQL -> "SELECT 1 FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?";
            case DUCKDB -> "SELECT 1 FROM information_schema.tables WHERE table_catalog = current_database() "
                    + "AND table_schema = 'main' AND table_name = ?";
            case CLICKHOUSE -> "SELECT 1 FROM information_schema.tables WHERE table_schema = currentDatabase() "
                    + "AND table_name = ?";
        };
    }

    /**
     * The JDBC URL of a source.
     *
     * @param settings the source
     * @return the URL
     */
    public @NotNull String url(@NotNull SourceSettings settings) {
        return switch (this) {
            case SQLITE -> "jdbc:sqlite:" + file(settings);
            case DUCKDB -> "jdbc:duckdb:" + file(settings);
            case MYSQL -> "jdbc:mariadb://" + host(settings) + ":" + settings.port() + "/" + settings.database();
            case CLICKHOUSE -> "jdbc:clickhouse:" + (settings.tls() ? "https" : "http") + "://" + host(settings) + ":"
                    + settings.port() + "/" + settings.database();
        };
    }

    /**
     * The JDBC connection properties of a source, credentials included. Never log them.
     *
     * @param settings the source
     * @return the properties
     */
    public @NotNull Properties properties(@NotNull SourceSettings settings) {
        Properties properties = new Properties();
        switch (this) {
            case SQLITE -> {
                SQLiteConfig config = new SQLiteConfig();
                config.setReadOnly(true);
                config.setBusyTimeout(15_000);
                properties.putAll(config.toProperties());
            }
            case DUCKDB -> properties.setProperty("duckdb.read_only", "true");
            case MYSQL -> {
                properties.setProperty("user", settings.username());
                properties.setProperty("password", settings.password());
                // CoreProtect's enable-ssl encrypts without checking the certificate; "trust" does the same.
                properties.setProperty("sslMode", settings.tls() ? "trust" : "disable");
                properties.setProperty("allowPublicKeyRetrieval", "true");
                properties.setProperty("connectTimeout", "10000");
            }
            case CLICKHOUSE -> {
                properties.setProperty("user", settings.username());
                properties.setProperty("password", settings.password());
                properties.setProperty("ssl", Boolean.toString(settings.tls()));
                // String columns hold CoreProtect's blobs; read them as raw bytes, as CoreProtect does.
                properties.setProperty("binary_string_support", "true");
                // CoreProtect's setting too: without it a newer server answers in a compression the driver
                // cannot read ("Invalid LZ4 magic byte").
                properties.setProperty("clickhouse_setting_network_compression_method", "LZ4");
                properties.setProperty("connection_timeout", "10000");
                properties.setProperty("socket_timeout", "300000");
            }
        }
        return properties;
    }

    /**
     * Prepares a fresh connection: makes a MySQL session read-only. MariaDB Connector/J's
     * {@code setReadOnly(true)} alone sends nothing to a single server (only with {@code assureReadOnly}),
     * so the session's access mode is set explicitly as well.
     *
     * @param connection the connection
     * @throws SQLException when the engine refuses
     */
    public void prepare(@NotNull Connection connection) throws SQLException {
        if (this == MYSQL) {
            connection.setReadOnly(true);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET SESSION TRANSACTION READ ONLY");
            }
        }
    }

    /**
     * A blob column's bytes. CoreProtect's ClickHouse views expose blobs as strings, which the driver
     * hands out as bytes ({@code binary_string_support}); a column the driver reports as an array carries
     * a leading presence byte (0), and an empty one is NULL.
     *
     * @param rows   the result set, on a row
     * @param column the 1-based column
     * @return the bytes, or {@code null} when NULL
     * @throws SQLException when the column cannot be read or the presence byte is wrong
     */
    public byte @Nullable [] bytes(@NotNull ResultSet rows, int column) throws SQLException {
        byte[] value = rows.getBytes(column);
        if (this != CLICKHOUSE || value == null || rows.getMetaData().getColumnType(column) != Types.ARRAY) {
            return value;
        }
        if (value.length == 0) {
            return null;
        }
        if (value[0] != 0) {
            throw new SQLException("unexpected ClickHouse binary presence marker in column " + column);
        }
        byte[] binary = new byte[value.length - 1];
        System.arraycopy(value, 1, binary, 0, binary.length);
        return binary;
    }

    private static @NotNull String file(@NotNull SourceSettings settings) {
        Path file = Objects.requireNonNull(settings.file(), "a file source has a file");
        return file.toAbsolutePath().toString();
    }

    private static @NotNull String host(@NotNull SourceSettings settings) {
        String host = settings.host();
        return host.indexOf(':') >= 0 && !host.startsWith("[") ? "[" + host + "]" : host;
    }

    /** How a source's tables are walked. */
    public enum ScanOrder {
        /** By rowid, in windows of row ids: insertion order, close to time order. */
        ROWID("rowid"),
        /** By {@code (time, rowid)}: ClickHouse reserves rowids in blocks per writer. */
        TIME("time");

        private final String key;

        ScanOrder(@NotNull String key) {
            this.key = key;
        }

        /**
         * The name stored in an import's checkpoint.
         *
         * @return {@code rowid} or {@code time}
         */
        public @NotNull String key() {
            return key;
        }
    }
}
