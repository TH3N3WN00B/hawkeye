package gg.moonrise.hawkeye.core.repository;

import gg.moonrise.hawkeye.core.sql.Database;
import gg.moonrise.hawkeye.core.sql.Sql;
import gg.moonrise.moss.spring.SpringComponent;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;

/**
 * The {@code lease} table: named, expiring locks that make shard-wide maintenance (auto-purge) run on
 * one server at a time. A holder renews its lease by acquiring it again; another server takes over
 * once it expires. Every statement is portable SQL (plus {@code Dialect#insertIgnoringDuplicates}),
 * and the result is always read back, so racing servers agree on the holder whatever update counts
 * their driver reports.
 */
@SpringComponent
public final class LeaseRepository {

    private static final List<String> COLUMNS = List.of("name", "holder", "expires");

    /**
     * Takes or renews a lease: creates it when absent, takes it over when expired, extends it when
     * {@code holder} already has it.
     *
     * @param connection a connection in auto-commit mode
     * @param sql        the shard's SQL context
     * @param name       the lease name
     * @param holder     who wants it (the server name)
     * @param now        the current time, Unix milliseconds
     * @param ttlMillis  how long the lease lasts from now
     * @return whether {@code holder} holds the lease afterwards
     * @throws SQLException when a statement fails
     */
    public boolean tryAcquire(@NotNull Connection connection, @NotNull Sql sql, @NotNull String name,
            @NotNull String holder, long now, long ttlMillis) throws SQLException {
        long expires = now + ttlMillis;
        try (PreparedStatement update = connection.prepareStatement("UPDATE " + sql.tables().lease()
                + " SET holder = ?, expires = ? WHERE name = ? AND (holder = ? OR expires <= ?)")) {
            update.setString(1, holder);
            update.setLong(2, expires);
            update.setString(3, name);
            update.setString(4, holder);
            update.setLong(5, now);
            if (update.executeUpdate() > 0) {
                return true;
            }
        }
        try (PreparedStatement insert = connection.prepareStatement(
                sql.dialect().insertIgnoringDuplicates(sql.tables().lease(), COLUMNS, 1))) {
            insert.setString(1, name);
            insert.setString(2, holder);
            insert.setLong(3, expires);
            insert.executeUpdate();
        }
        return holder(connection, sql, name, now).filter(holder::equals).isPresent();
    }

    /**
     * Gives a lease up, if {@code holder} has it.
     *
     * @param connection a connection in auto-commit mode
     * @param sql        the shard's SQL context
     * @param name       the lease name
     * @param holder     who gives it up
     * @return whether a lease was released
     * @throws SQLException when the statement fails
     */
    public boolean release(@NotNull Connection connection, @NotNull Sql sql, @NotNull String name,
            @NotNull String holder) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM " + sql.tables().lease() + " WHERE name = ? AND holder = ?")) {
            delete.setString(1, name);
            delete.setString(2, holder);
            return delete.executeUpdate() > 0;
        }
    }

    /**
     * Who holds a lease now.
     *
     * @param connection a connection
     * @param sql        the shard's SQL context
     * @param name       the lease name
     * @param now        the current time, Unix milliseconds
     * @return the holder, or empty when nobody holds an unexpired lease
     * @throws SQLException when the query fails
     */
    public @NotNull Optional<String> holder(@NotNull Connection connection, @NotNull Sql sql, @NotNull String name,
            long now) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT holder FROM " + sql.tables().lease() + " WHERE name = ? AND expires > ?")) {
            select.setString(1, name);
            select.setLong(2, now);
            try (ResultSet rows = select.executeQuery()) {
                return rows.next() ? Optional.of(rows.getString(1)) : Optional.empty();
            }
        }
    }

    /**
     * {@link #tryAcquire(Connection, Sql, String, String, long, long)} on a borrowed connection.
     *
     * @param database  the shard's database
     * @param name      the lease name
     * @param holder    who wants it
     * @param now       the current time, Unix milliseconds
     * @param ttlMillis how long the lease lasts from now
     * @return whether {@code holder} holds the lease afterwards
     * @throws SQLException when a statement fails
     */
    public boolean tryAcquire(@NotNull Database database, @NotNull String name, @NotNull String holder, long now,
            long ttlMillis) throws SQLException {
        return database.read(connection -> tryAcquire(connection, database.sql(), name, holder, now, ttlMillis));
    }

    /**
     * {@link #release(Connection, Sql, String, String)} on a borrowed connection.
     *
     * @param database the shard's database
     * @param name     the lease name
     * @param holder   who gives it up
     * @return whether a lease was released
     * @throws SQLException when the statement fails
     */
    public boolean release(@NotNull Database database, @NotNull String name, @NotNull String holder)
            throws SQLException {
        return database.read(connection -> release(connection, database.sql(), name, holder));
    }
}
