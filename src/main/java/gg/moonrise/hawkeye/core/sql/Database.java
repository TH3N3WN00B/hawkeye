package gg.moonrise.hawkeye.core.sql;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

/**
 * One shard's connection pool with its dialect and table names. Borrow connections only through
 * {@link #read} and {@link #transaction}, which always return them.
 */
@Slf4j
public final class Database implements AutoCloseable {

    private final @NotNull String name;
    private final @NotNull Dialect dialect;
    private final @NotNull Tables tables;
    private final @NotNull DataSource dataSource;
    private final @NotNull AutoCloseable closer;

    /**
     * Wraps a pool.
     *
     * @param name       the shard name
     * @param dialect    the engine
     * @param tables     the table names
     * @param dataSource the pool
     * @param closer     closes the pool
     */
    public Database(@NotNull String name, @NotNull Dialect dialect, @NotNull Tables tables,
            @NotNull DataSource dataSource, @NotNull AutoCloseable closer) {
        this.name = name;
        this.dialect = dialect;
        this.tables = tables;
        this.dataSource = dataSource;
        this.closer = closer;
    }

    /**
     * The shard name.
     *
     * @return {@code home} or the configured remote shard name
     */
    public @NotNull String name() {
        return name;
    }

    /**
     * The engine.
     *
     * @return the dialect
     */
    public @NotNull Dialect dialect() {
        return dialect;
    }

    /**
     * The table names.
     *
     * @return the tables
     */
    public @NotNull Tables tables() {
        return tables;
    }

    /**
     * The dialect and table names together, as repositories take them.
     *
     * @return the SQL context
     */
    public @NotNull Sql sql() {
        return new Sql(dialect, tables);
    }

    /**
     * Runs work on a connection in auto-commit mode.
     *
     * @param work the work
     * @param <T>  the result type
     * @return the work's result
     * @throws SQLException when borrowing or the work fails
     */
    public <T> T read(@NotNull SqlWork<T> work) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return work.run(connection);
        }
    }

    /**
     * Runs work in one transaction: committed when it returns, rolled back when it throws anything (an
     * {@link Error} too, before auto-commit is restored: under JDBC restoring it commits an open
     * transaction).
     *
     * @param work the work
     * @param <T>  the result type
     * @return the work's result
     * @throws SQLException when borrowing, the work or the commit fails
     */
    public <T> T transaction(@NotNull SqlWork<T> work) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (Throwable e) {
                rollbackQuietly(connection, e);
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        }
    }

    /** Closes the pool; a failure is logged, not thrown, so shutdown continues. */
    @Override
    public void close() {
        try {
            closer.close();
        } catch (Exception e) {
            log.warn("Could not close the {} pool", name, e);
        }
    }

    private static void rollbackQuietly(@NotNull Connection connection, @NotNull Throwable cause) {
        try {
            connection.rollback();
        } catch (SQLException rollback) {
            cause.addSuppressed(rollback);
        }
    }
}
