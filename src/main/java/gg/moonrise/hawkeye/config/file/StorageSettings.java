package gg.moonrise.hawkeye.config.file;

import de.exlll.configlib.Comment;
import de.exlll.configlib.Configuration;
import de.exlll.configlib.SerializeWith;
import gg.moonrise.hawkeye.config.env.EnvReferences;
import gg.moonrise.hawkeye.config.env.EnvValue;
import gg.moonrise.hawkeye.config.env.Environment;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A database connection: the home shard ({@code storage}) or a remote shard ({@code network.shards}).
 *
 * <p>The user, the password and the parameter values may hold {@code ${NAME}} references to environment
 * variables ({@link EnvReferences}); the defaults read {@code HAWKEYE_DB_USERNAME} and
 * {@code HAWKEYE_DB_PASSWORD}. The object keeps the text as written: references are resolved only when a
 * connection is opened ({@link #login}), and none of the three is reachable through a getter or shown by
 * {@link #toString()}.
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
@ToString
@Configuration
public final class StorageSettings {

    /** The environment variable the default {@code username} reads. */
    public static final String USERNAME_VARIABLE = "HAWKEYE_DB_USERNAME";

    /** The environment variable the default {@code password} reads. */
    public static final String PASSWORD_VARIABLE = "HAWKEYE_DB_PASSWORD";

    private static final Pattern PREFIX = Pattern.compile("[a-z0-9_]{0,24}");

    @Comment("SQLITE, MYSQL, MARIADB or POSTGRESQL.")
    private StorageType type = StorageType.SQLITE;

    @Comment("SQLite only: the database file inside the plugin folder.")
    private String sqliteFile = "hawkeye.db";

    private String host = "localhost";

    @Comment("0 uses the engine's default port (3306 or 5432).")
    private int port = 0;

    private String database = "hawkeye";

    @Comment({"The user and password. Environment variables are the recommended way: ${NAME} is replaced by the",
            "variable NAME when Hawkeye connects, and an unset variable is a configuration error. A literal value",
            "works too. Give each shard its own variables (e.g. ${HAWKEYE_SHARD_EU_PASSWORD}). Hawkeye never logs",
            "or shows these values."})
    @Getter(AccessLevel.NONE)
    @ToString.Exclude
    @SerializeWith(serializer = TextValueSerializer.class)
    private String username = "${" + USERNAME_VARIABLE + "}";

    @Getter(AccessLevel.NONE)
    @ToString.Exclude
    @SerializeWith(serializer = TextValueSerializer.class)
    private String password = "${" + PASSWORD_VARIABLE + "}";

    @Comment({"Extra driver parameters, e.g. sslMode: verify-full. Values may use ${NAME} as well.",
            "MySQL and MariaDB connect without TLS (sslMode: disable) unless sslMode or useSsl is set here."})
    @Getter(AccessLevel.NONE)
    @ToString.Exclude
    @SerializeWith(serializer = TextValueSerializer.class, nesting = 1)
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
     * The credentials with every {@code ${NAME}} reference resolved. Validation ({@link #problems}) makes
     * sure every referenced variable is set for a server engine.
     *
     * @param environment where variables are looked up
     * @return the user, password and parameters
     * @throws IllegalStateException when a referenced variable is not set; the message names the variable
     */
    public @NotNull StorageLogin login(@NotNull Environment environment) {
        String user = EnvReferences.require(text(username), environment, "username").value();
        String pass = EnvReferences.require(text(password), environment, "password").value();
        Map<String, String> resolved = new LinkedHashMap<>();
        List<String> secrets = new ArrayList<>();
        parameters().forEach((key, value) -> {
            EnvValue.Resolved parameter = EnvReferences.require(text(value), environment, "parameters." + key);
            resolved.put(key, parameter.value());
            if (parameter.substituted()) {
                secrets.add(parameter.value());
            }
        });
        return new StorageLogin(user, pass, resolved, secrets);
    }

    /**
     * Settings for a MySQL, MariaDB or PostgreSQL server (tests and tools; operators use config.yml).
     *
     * @param type        the engine
     * @param host        the host
     * @param port        the port, 0 for the engine's default
     * @param database    the database
     * @param username    the user
     * @param password    the password
     * @param tablePrefix the table prefix
     * @return the settings
     */
    public static @NotNull StorageSettings server(@NotNull StorageType type, @NotNull String host, int port,
            @NotNull String database, @NotNull String username, @NotNull String password,
            @NotNull String tablePrefix) {
        StorageSettings settings = new StorageSettings();
        settings.type = type;
        settings.host = host;
        settings.port = port;
        settings.database = database;
        settings.username = username;
        settings.password = password;
        settings.tablePrefix = tablePrefix;
        return settings;
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
     * Validation problems. A server engine needs every variable its user, password and parameters
     * reference; a problem names the variable, never a value.
     *
     * @param path        the section path for messages
     * @param environment where variables are looked up
     * @return one message per problem
     */
    public @NotNull List<String> problems(@NotNull String path, @NotNull Environment environment) {
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
        if (type != StorageType.SQLITE) {
            unset(problems, path + ".username", username, environment);
            unset(problems, path + ".password", password, environment);
            parameters().forEach((key, value) -> unset(problems, path + ".parameters." + key, value, environment));
        }
        return problems;
    }

    private @NotNull Map<String, String> parameters() {
        return Objects.requireNonNullElse(parameters, Map.of());
    }

    private static void unset(@NotNull List<String> problems, @NotNull String key, @Nullable String value,
            @NotNull Environment environment) {
        EnvReferences.unset(text(value), environment)
                .forEach(variable -> problems.add(EnvReferences.unsetMessage(key, variable)));
    }

    /** An empty YAML value reads as {@code null}; it means no text. */
    private static @NotNull String text(@Nullable String value) {
        return Objects.requireNonNullElse(value, "");
    }
}
