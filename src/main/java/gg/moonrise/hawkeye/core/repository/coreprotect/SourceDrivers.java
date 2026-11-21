package gg.moonrise.hawkeye.core.repository.coreprotect;

import gg.moonrise.hawkeye.entity.importer.SourceType;
import gg.moonrise.moss.spring.SpringComponent;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.nio.file.Path;
import java.sql.Driver;
import java.sql.SQLException;
import lombok.RequiredArgsConstructor;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.sqlite.JDBC;

/**
 * The JDBC driver of each CoreProtect engine. SQLite and MySQL/MariaDB use the drivers Hawkeye loads
 * anyway ({@code org.sqlite.JDBC}, and {@code org.mariadb.jdbc.Driver}, which talks to MySQL servers
 * too). DuckDB (80 MB) and ClickHouse (12 MB) are not loaded with the plugin: their pinned jars are
 * resolved on first use by a {@link DriverResolver} over {@code <plugin data folder>/drivers}, downloaded
 * from Maven Central when missing. The class loaders close with the Spring context.
 */
@SpringComponent
@RequiredArgsConstructor
public final class SourceDrivers {

    /** Where missing driver jars are downloaded from. */
    public static final URI MAVEN_CENTRAL = URI.create("https://repo1.maven.org/maven2/");

    /** The folder below the plugin's data folder that holds driver jars. */
    public static final String FOLDER = "drivers";

    private final @NotNull JavaPlugin plugin;

    private DriverResolver resolver;

    /**
     * The driver of an engine.
     *
     * @param type the engine
     * @return a driver instance; connect with {@link Driver#connect}, never through {@code DriverManager}
     * @throws SQLException when an on-demand driver cannot be found, downloaded, verified or loaded
     */
    public @NotNull Driver driver(@NotNull SourceType type) throws SQLException {
        return switch (type) {
            case SQLITE -> new JDBC();
            // The name clashes with java.sql.Driver.
            case MYSQL -> new org.mariadb.jdbc.Driver();
            case DUCKDB -> resolver().driver(DriverArtifact.DUCKDB);
            case CLICKHOUSE -> resolver().driver(DriverArtifact.CLICKHOUSE);
        };
    }

    /**
     * The folder driver jars are kept in.
     *
     * @return {@code <plugin data folder>/drivers}
     */
    public @NotNull Path folder() {
        return plugin.getDataPath().resolve(FOLDER);
    }

    /** Closes the class loaders of the drivers loaded from jars. */
    @PreDestroy
    public synchronized void close() {
        if (resolver != null) {
            resolver.close();
            resolver = null;
        }
    }

    private synchronized @NotNull DriverResolver resolver() {
        if (resolver == null) {
            resolver = new DriverResolver(folder(), MAVEN_CENTRAL, plugin.getClass().getClassLoader());
        }
        return resolver;
    }
}
