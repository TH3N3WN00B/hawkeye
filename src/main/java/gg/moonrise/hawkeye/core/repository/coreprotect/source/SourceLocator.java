package gg.moonrise.hawkeye.core.repository.coreprotect.source;

import gg.moonrise.hawkeye.core.repository.coreprotect.schema.SourceDialect;
import gg.moonrise.hawkeye.entity.importer.source.SourceLocation;
import gg.moonrise.hawkeye.entity.importer.source.SourceSettings;
import gg.moonrise.hawkeye.entity.importer.source.SourceType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.yaml.snakeyaml.error.MarkedYAMLException;

/**
 * Turns the operator's argument into a CoreProtect database. A folder with a {@code config.yml} is read
 * as CoreProtect's data folder and its config decides; a folder without one is probed for
 * {@code database.db} (SQLite), then {@code database.duckdb} (DuckDB). A {@code .yml}/{@code .yaml}
 * file is a CoreProtect config (its folder is the data folder), a {@code .duckdb} file a DuckDB
 * database and any other file a SQLite database. Reads files only; never connects.
 */
public final class SourceLocator {

    /** CoreProtect's config file in its data folder. */
    public static final String CONFIG = "config.yml";

    /** CoreProtect's SQLite file name, unless {@code sqlite-database} names another. */
    public static final String SQLITE_FILE = "database.db";

    /** CoreProtect's DuckDB file name. */
    public static final String DUCKDB_FILE = "database.duckdb";

    private static final int MYSQL_PORT = 3306;
    private static final int CLICKHOUSE_PORT = 8123;

    /**
     * Resolves an argument.
     *
     * @param argument      a folder or file, or {@code null}/blank for {@code defaultFolder}
     * @param defaultFolder CoreProtect's data folder ({@code plugins/CoreProtect})
     * @param baseFolder    what a relative argument is resolved against (the server folder)
     * @return the database found, or why none was
     */
    public @NotNull SourceLocation locate(@Nullable String argument, @NotNull Path defaultFolder,
            @NotNull Path baseFolder) {
        Path path = argument == null || argument.isBlank() ? defaultFolder : baseFolder.resolve(argument.strip());
        path = path.toAbsolutePath().normalize();
        if (Files.isDirectory(path)) {
            return folder(path);
        }
        if (!Files.isRegularFile(path)) {
            return new SourceLocation.Missing(path.toString());
        }
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".yml") || name.endsWith(".yaml")) {
            return config(path, Objects.requireNonNull(path.getParent(), "a file has a folder"));
        }
        return found(SourceSettings.ofFile(name.endsWith(".duckdb") ? SourceType.DUCKDB : SourceType.SQLITE, path));
    }

    private @NotNull SourceLocation folder(@NotNull Path folder) {
        Path config = folder.resolve(CONFIG);
        if (Files.isRegularFile(config)) {
            return config(config, folder);
        }
        if (Files.isRegularFile(folder.resolve(SQLITE_FILE))) {
            return found(SourceSettings.ofFile(SourceType.SQLITE, folder.resolve(SQLITE_FILE)));
        }
        if (Files.isRegularFile(folder.resolve(DUCKDB_FILE))) {
            return found(SourceSettings.ofFile(SourceType.DUCKDB, folder.resolve(DUCKDB_FILE)));
        }
        return new SourceLocation.Missing(folder.toString());
    }

    private @NotNull SourceLocation config(@NotNull Path file, @NotNull Path folder) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file.toFile());
        } catch (IOException e) {
            return new SourceLocation.Invalid("cannot read " + file + ": " + e.getMessage());
        } catch (InvalidConfigurationException e) {
            // SnakeYAML's message quotes the broken line, which may be the mysql-password line.
            return new SourceLocation.Invalid("cannot read " + file + ": it is not valid YAML" + where(e));
        }
        String type = yaml.getString("database-type");
        if (type == null || type.isBlank()) {
            type = yaml.getBoolean("use-mysql", false) ? "mysql" : "sqlite";
        }
        String prefix = yaml.getString("table-prefix", SourceSettings.DEFAULT_PREFIX);
        return switch (type.strip().toLowerCase(Locale.ROOT)) {
            case "sqlite" -> sqlite(yaml, folder);
            case "duckdb" -> file(SourceType.DUCKDB, folder.resolve(DUCKDB_FILE));
            case "mysql" -> server(SourceType.MYSQL, prefix, yaml.getString("mysql-host", "127.0.0.1"),
                    port(yaml, "mysql-port", MYSQL_PORT), yaml.getString("mysql-database", "database"),
                    yaml.getString("mysql-username", "root"), yaml.getString("mysql-password", ""),
                    yaml.getBoolean("enable-ssl", false));
            case "clickhouse" -> server(SourceType.CLICKHOUSE, prefix, yaml.getString("clickhouse-host", "127.0.0.1"),
                    port(yaml, "clickhouse-port", CLICKHOUSE_PORT), yaml.getString("clickhouse-database", "default"),
                    yaml.getString("clickhouse-username", "default"), yaml.getString("clickhouse-password", ""),
                    yaml.getBoolean("clickhouse-tls", false));
            default -> new SourceLocation.Invalid("unknown database-type \"" + type + "\" in " + file);
        };
    }

    private @NotNull SourceLocation sqlite(@NotNull YamlConfiguration yaml, @NotNull Path folder) {
        String name = yaml.getString("sqlite-database", SQLITE_FILE);
        if (name == null || name.isBlank() || name.contains("/") || name.contains("\\") || name.equals("..")) {
            return new SourceLocation.Invalid("invalid sqlite-database \"" + name + "\"");
        }
        return file(SourceType.SQLITE, folder.resolve(name.strip()));
    }

    private static @NotNull SourceLocation file(@NotNull SourceType type, @NotNull Path file) {
        return Files.isRegularFile(file) ? found(SourceSettings.ofFile(type, file))
                : new SourceLocation.Missing(file.toString());
    }

    private static @NotNull SourceLocation server(@NotNull SourceType type, @Nullable String prefix,
            @Nullable String host, int port, @Nullable String database, @Nullable String username,
            @Nullable String password, boolean tls) {
        String tablePrefix = prefix == null ? SourceSettings.DEFAULT_PREFIX : prefix.strip();
        if (!SourceDialect.validPrefix(tablePrefix)) {
            return new SourceLocation.Invalid("invalid table-prefix \"" + tablePrefix
                    + "\": it must match [A-Za-z_][A-Za-z0-9_]*");
        }
        String engine = type.name().toLowerCase(Locale.ROOT);
        if (host == null || host.isBlank()) {
            return new SourceLocation.Invalid("no " + engine + " host is configured");
        }
        if (!SourceDialect.validHost(host.strip())) {
            return new SourceLocation.Invalid("invalid " + engine + " host \"" + host.strip()
                    + "\": it must be a host name or an IP address");
        }
        if (port < 1 || port > 65_535) {
            return new SourceLocation.Invalid("invalid " + engine + " port " + port);
        }
        String name = Objects.requireNonNullElse(database, "").strip();
        if (!SourceDialect.validDatabase(name)) {
            return new SourceLocation.Invalid("invalid " + engine + " database \"" + name
                    + "\": it must match [A-Za-z0-9_$-]+");
        }
        return found(SourceSettings.ofServer(type, host.strip(), port, name, Objects.requireNonNullElse(username, ""),
                Objects.requireNonNullElse(password, ""), tablePrefix, tls));
    }

    /** A port written as a number or as a quoted number; 0 when it is neither. */
    private static int port(@NotNull YamlConfiguration yaml, @NotNull String key, int fallback) {
        Object value = yaml.get(key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(value.toString().strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Where a YAML error is, without the text of the line: {@code  (line 4, column 17)}, or nothing. */
    private static @NotNull String where(@NotNull InvalidConfigurationException error) {
        for (Throwable cause = error.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof MarkedYAMLException marked && marked.getProblemMark() != null) {
                return " (line " + (marked.getProblemMark().getLine() + 1) + ", column "
                        + (marked.getProblemMark().getColumn() + 1) + ")";
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return "";
    }

    private static @NotNull SourceLocation found(@NotNull SourceSettings settings) {
        return new SourceLocation.Found(settings);
    }
}
