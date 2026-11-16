package gg.moonrise.hawkeye.config.file;

import de.exlll.configlib.Comment;
import de.exlll.configlib.Configuration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;
import org.jetbrains.annotations.NotNull;

/**
 * A database connection: the home shard ({@code storage}) or a remote shard ({@code network.shards}).
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
@ToString
@Configuration
public final class StorageSettings {

    private static final Pattern PREFIX = Pattern.compile("[a-z0-9_]{0,24}");

    @Comment("SQLITE, MYSQL, MARIADB or POSTGRESQL.")
    private StorageType type = StorageType.SQLITE;

    @Comment("SQLite only: the database file inside the plugin folder.")
    private String sqliteFile = "hawkeye.db";

    private String host = "localhost";

    @Comment("0 uses the engine's default port (3306 or 5432).")
    private int port = 0;

    private String database = "hawkeye";

    private String username = "hawkeye";

    @ToString.Exclude
    private String password = "";

    @Comment("Extra JDBC URL parameters, e.g. sslMode: verify-full.")
    private Map<String, String> parameters = Map.of();

    @Comment("Prefix of every table. Servers that share a database must use the same prefix.")
    private String tablePrefix = "hawkeye_";

    @Comment("Connections in the pool: one for the writer, the rest for lookups and rollbacks.")
    private int poolSize = 6;

    private long connectionTimeoutMillis = 10_000;

    /**
     * The port to connect to.
     *
     * @return the configured port, or the engine's default when it is 0
     */
    public int effectivePort() {
        return port > 0 ? port : type.defaultPort();
    }

    /**
     * SQLite settings for a file with this table prefix and pool configuration (tests and tools).
     *
     * @param file the file path, relative to the plugin folder
     * @return the new settings
     */
    public @NotNull StorageSettings withSqliteFile(@NotNull String file) {
        StorageSettings copy = new StorageSettings();
        copy.type = StorageType.SQLITE;
        copy.sqliteFile = file;
        copy.tablePrefix = tablePrefix;
        copy.poolSize = poolSize;
        copy.connectionTimeoutMillis = connectionTimeoutMillis;
        return copy;
    }

    /**
     * Validation problems.
     *
     * @param path the section path for messages
     * @return one message per problem
     */
    public @NotNull List<String> problems(@NotNull String path) {
        List<String> problems = new ArrayList<>();
        if (!PREFIX.matcher(tablePrefix).matches()) {
            problems.add(path + ".table-prefix must be up to 24 of a-z, 0-9 and _: '" + tablePrefix + "'");
        }
        if (poolSize < 2 || poolSize > 64) {
            problems.add(path + ".pool-size must be between 2 and 64");
        }
        if (type == StorageType.SQLITE && sqliteFile.isBlank()) {
            problems.add(path + ".sqlite-file must not be blank");
        }
        if (type != StorageType.SQLITE && (host.isBlank() || database.isBlank())) {
            problems.add(path + ".host and " + path + ".database must be set for " + type);
        }
        if (connectionTimeoutMillis < 250) {
            problems.add(path + ".connection-timeout-millis must be at least 250");
        }
        return problems;
    }
}
