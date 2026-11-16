package gg.moonrise.hawkeye.core.sql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import gg.moonrise.hawkeye.config.file.StorageSettings;
import gg.moonrise.hawkeye.config.file.StorageType;
import gg.moonrise.moss.spring.SpringComponent;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;

/**
 * Opens a HikariCP pool for a shard. SQLite runs in WAL mode (readers never block the writer) with
 * {@code synchronous=NORMAL} and a busy timeout; the server engines use the MariaDB and PostgreSQL
 * drivers resolved by the plugin loader.
 */
@SpringComponent
public final class DatabaseFactory {

    /**
     * Opens a pool. Nothing is read or written yet; the first connection is made on first use.
     *
     * @param shard      the shard name, used in the pool name
     * @param settings   the connection settings
     * @param dataFolder the plugin folder SQLite files are relative to
     * @return the database
     */
    public @NotNull Database open(@NotNull String shard, @NotNull StorageSettings settings, @NotNull Path dataFolder) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("Hawkeye-" + shard);
        config.setMaximumPoolSize(settings.type() == StorageType.SQLITE ? Math.min(settings.poolSize(), 4)
                : settings.poolSize());
        config.setMinimumIdle(1);
        config.setConnectionTimeout(settings.connectionTimeoutMillis());
        config.setValidationTimeout(Math.min(5_000, settings.connectionTimeoutMillis()));
        // Never fail at construction: a database that is down at start is retried, not fatal.
        config.setInitializationFailTimeout(-1);
        switch (settings.type()) {
            case SQLITE -> sqlite(config, dataFolder.resolve(settings.sqliteFile()));
            case MYSQL, MARIADB -> mysql(config, settings);
            case POSTGRESQL -> postgres(config, settings);
        }
        HikariDataSource dataSource = new HikariDataSource(config);
        return new Database(shard, Dialect.of(settings.type()), new Tables(settings.tablePrefix()), dataSource,
                dataSource);
    }

    private static void sqlite(@NotNull HikariConfig config, @NotNull Path file) {
        config.setDriverClassName("org.sqlite.JDBC");
        config.setJdbcUrl("jdbc:sqlite:" + file.toAbsolutePath());
        config.addDataSourceProperty("journal_mode", "WAL");
        config.addDataSourceProperty("synchronous", "NORMAL");
        config.addDataSourceProperty("busy_timeout", "15000");
        config.addDataSourceProperty("foreign_keys", "false");
    }

    private static void mysql(@NotNull HikariConfig config, @NotNull StorageSettings settings) {
        config.setDriverClassName("org.mariadb.jdbc.Driver");
        config.setJdbcUrl("jdbc:mariadb://" + settings.host() + ":" + settings.effectivePort() + "/"
                + settings.database() + query(settings.parameters()));
        config.setUsername(settings.username());
        config.setPassword(settings.password());
        config.addDataSourceProperty("cachePrepStmts", "true");
    }

    private static void postgres(@NotNull HikariConfig config, @NotNull StorageSettings settings) {
        config.setDriverClassName("org.postgresql.Driver");
        config.setJdbcUrl("jdbc:postgresql://" + settings.host() + ":" + settings.effectivePort() + "/"
                + settings.database() + query(settings.parameters()));
        config.setUsername(settings.username());
        config.setPassword(settings.password());
    }

    private static @NotNull String query(@NotNull Map<String, String> parameters) {
        if (parameters.isEmpty()) {
            return "";
        }
        return parameters.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("&", "?", ""));
    }
}
