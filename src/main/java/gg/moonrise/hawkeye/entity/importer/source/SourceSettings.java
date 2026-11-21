package gg.moonrise.hawkeye.entity.importer.source;

import gg.moonrise.hawkeye.util.redact.Redactor;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Where a CoreProtect database is and how to open it. {@code file} is used by {@link SourceType#SQLITE}
 * and {@link SourceType#DUCKDB}; host, port, database, username, password and tls by
 * {@link SourceType#MYSQL} and {@link SourceType#CLICKHOUSE}. Neither the username nor the password
 * reaches a log or a message: {@link #toString()} is {@link #describe()}, and a driver's message passes
 * through {@link #redactor()}.
 *
 * @param type     the engine
 * @param file     the database file, or {@code null} for a server engine
 * @param host     the server host, or empty
 * @param port     the server port, or 0
 * @param database the server database, or empty
 * @param username the server user, or empty
 * @param password the server password, or empty
 * @param prefix   the table prefix ({@code co_} for the file engines)
 * @param tls      whether the server connection is encrypted
 */
public record SourceSettings(
        @NotNull SourceType type,
        @Nullable Path file,
        @NotNull String host,
        int port,
        @NotNull String database,
        @NotNull String username,
        @NotNull String password,
        @NotNull String prefix,
        boolean tls) {

    /** The table prefix of the file engines, and the default of the server engines. */
    public static final String DEFAULT_PREFIX = "co_";

    /**
     * Checks the values.
     *
     * @throws NullPointerException     when a value is missing
     * @throws IllegalArgumentException when a file engine has no file
     */
    public SourceSettings {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(password, "password");
        Objects.requireNonNull(prefix, "prefix");
        if ((type == SourceType.SQLITE || type == SourceType.DUCKDB) && file == null) {
            throw new IllegalArgumentException(type + " needs a database file");
        }
    }

    /**
     * Settings of a file engine, with the fixed {@link #DEFAULT_PREFIX}.
     *
     * @param type {@link SourceType#SQLITE} or {@link SourceType#DUCKDB}
     * @param file the database file
     * @return the settings
     */
    public static @NotNull SourceSettings ofFile(@NotNull SourceType type, @NotNull Path file) {
        return new SourceSettings(type, file, "", 0, "", "", "", DEFAULT_PREFIX, false);
    }

    /**
     * Settings of a server engine.
     *
     * @param type     {@link SourceType#MYSQL} or {@link SourceType#CLICKHOUSE}
     * @param host     the host
     * @param port     the port
     * @param database the database
     * @param username the user
     * @param password the password
     * @param prefix   the table prefix
     * @param tls      whether the connection is encrypted
     * @return the settings
     */
    public static @NotNull SourceSettings ofServer(@NotNull SourceType type, @NotNull String host, int port,
            @NotNull String database, @NotNull String username, @NotNull String password, @NotNull String prefix,
            boolean tls) {
        return new SourceSettings(type, null, host, port, database, username, password, prefix, tls);
    }

    /**
     * A description for messages and logs, without the username and the password:
     * {@code sqlite /srv/plugins/CoreProtect/database.db}, {@code mysql 127.0.0.1:3306/minecraft}.
     *
     * @return the description
     */
    public @NotNull String describe() {
        String engine = type.name().toLowerCase(Locale.ROOT);
        String where = file != null ? file.toString() : host + ":" + port + "/" + database;
        String extras = (prefix.equals(DEFAULT_PREFIX) ? "" : " prefix " + prefix) + (tls ? " tls" : "");
        return engine + " " + where + extras;
    }

    /**
     * What a driver's message about this source must not show: the password anywhere, the username where
     * it stands on its own ({@code Access denied for user 'root'@'10.0.0.5'}).
     *
     * @return the redactor
     */
    public @NotNull Redactor redactor() {
        return Redactor.of(List.of(password), List.of(username));
    }

    @Override
    public @NotNull String toString() {
        return describe();
    }
}
