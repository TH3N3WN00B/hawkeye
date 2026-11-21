package gg.moonrise.hawkeye.core.sql;

import gg.moonrise.hawkeye.util.redact.RedactedErrors;
import gg.moonrise.hawkeye.util.redact.Redactor;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Opens connections of a server engine for the pool, keeping the credentials to itself. The user, the
 * password and the driver parameters travel as driver properties, never in the URL or in Hikari's
 * configuration, and every exception the driver throws passes through a {@link Redactor} first
 * ({@link RedactedErrors}): a message such as {@code Access denied for user 'hawkeye'@'10.0.0.5'} reaches
 * the pool, the log and {@code /hawkeye status} without the name. {@link #toString()} shows the URL only.
 */
public final class RedactingDataSource implements DataSource {

    private final @NotNull Driver driver;
    private final @NotNull String url;
    private final @NotNull Properties properties;
    private final @NotNull Redactor redactor;
    private volatile int loginTimeoutSeconds;
    private volatile PrintWriter logWriter;

    /**
     * A data source over a driver.
     *
     * @param driver     the engine's driver, used directly (never through {@code DriverManager})
     * @param url        the JDBC URL; it must not carry a credential
     * @param properties the connection properties, credentials included; copied
     * @param redactor   what the driver's messages must not show
     */
    public RedactingDataSource(@NotNull Driver driver, @NotNull String url, @NotNull Properties properties,
            @NotNull Redactor redactor) {
        this.driver = driver;
        this.url = url;
        this.properties = copy(properties);
        this.redactor = redactor;
    }

    @Override
    public @NotNull Connection getConnection() throws SQLException {
        Connection connection;
        try {
            // A copy each time: MariaDB Connector/J writes the URL's parameters into the properties it is given.
            connection = driver.connect(url, copy(properties));
        } catch (SQLException e) {
            throw RedactedErrors.safe(e, redactor);
        } catch (RuntimeException e) {
            if (RedactedErrors.reveals(e, redactor)) {
                throw RedactedErrors.rebuild(e, redactor);
            }
            throw e;
        }
        if (connection == null) {
            throw new SQLException("the " + driver.getClass().getName() + " driver does not accept " + url);
        }
        return connection;
    }

    /**
     * Not supported: the credentials are fixed when the data source is made.
     *
     * @param username ignored
     * @param password ignored
     * @return never
     * @throws SQLFeatureNotSupportedException always
     */
    @Override
    public @NotNull Connection getConnection(@Nullable String username, @Nullable String password)
            throws SQLException {
        throw new SQLFeatureNotSupportedException("the credentials of this data source are fixed");
    }

    @Override
    public @Nullable PrintWriter getLogWriter() {
        return logWriter;
    }

    @Override
    public void setLogWriter(@Nullable PrintWriter out) {
        this.logWriter = out;
    }

    /**
     * Remembers the login timeout the pool sets. The drivers get theirs from the connect timeout
     * properties {@link DatabaseFactory} sets, not from here.
     *
     * @param seconds the timeout
     */
    @Override
    public void setLoginTimeout(int seconds) {
        this.loginTimeoutSeconds = seconds;
    }

    @Override
    public int getLoginTimeout() {
        return loginTimeoutSeconds;
    }

    @Override
    public @NotNull Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException("this data source does not log through java.util.logging");
    }

    @Override
    public <T> @NotNull T unwrap(@NotNull Class<T> type) throws SQLException {
        if (type.isInstance(this)) {
            return type.cast(this);
        }
        throw new SQLException("not a wrapper for " + type.getName());
    }

    @Override
    public boolean isWrapperFor(@NotNull Class<?> type) {
        return type.isInstance(this);
    }

    @Override
    public @NotNull String toString() {
        return "RedactingDataSource[" + url + "]";
    }

    private static @NotNull Properties copy(@NotNull Properties properties) {
        Properties copy = new Properties();
        properties.stringPropertyNames().forEach(key -> copy.setProperty(key, properties.getProperty(key)));
        return copy;
    }
}
