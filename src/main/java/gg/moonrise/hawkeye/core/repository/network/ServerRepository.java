package gg.moonrise.hawkeye.core.repository.network;

import gg.moonrise.hawkeye.core.sql.Sql;
import gg.moonrise.hawkeye.entity.server.ServerNode;
import gg.moonrise.hawkeye.util.event.EventIds;
import gg.moonrise.moss.spring.SpringComponent;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;

/**
 * The {@code server} table: every server registered in a shard and its node id. A server keeps its
 * node id for good once registered under its name.
 */
@SpringComponent
public final class ServerRepository {

    private static final List<String> COLUMNS = List.of("name", "scope", "last_seen");

    /**
     * Registers a server (or refreshes its scope and heartbeat) and returns its node. An existing row is
     * updated rather than upserted: PostgreSQL identities and InnoDB auto-increments consume an id even
     * for a conflicting insert, and node ids end at {@link EventIds#MAX_NODE}.
     *
     * @param connection a connection in auto-commit mode
     * @param sql        the shard's SQL context
     * @param name       the server name
     * @param scope      its world scope
     * @param now        the current time, Unix milliseconds
     * @return the registered node
     * @throws SQLException when a statement fails or the shard has no node id left
     */
    public @NotNull ServerNode register(@NotNull Connection connection, @NotNull Sql sql, @NotNull String name,
            @NotNull String scope, long now) throws SQLException {
        Optional<ServerNode> existing = byName(connection, sql, name);
        if (existing.isPresent()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE " + sql.tables().server() + " SET scope = ?, last_seen = ? WHERE name = ?")) {
                statement.setString(1, scope);
                statement.setLong(2, now);
                statement.setString(3, name);
                statement.executeUpdate();
            }
        } else {
            String insert = sql.dialect().insertIgnoringDuplicates(sql.tables().server(), COLUMNS, 1);
            try (PreparedStatement statement = connection.prepareStatement(insert)) {
                statement.setString(1, name);
                statement.setString(2, scope);
                statement.setLong(3, now);
                statement.executeUpdate();
            }
        }
        ServerNode node = byName(connection, sql, name)
                .orElseThrow(() -> new SQLException("server " + name + " vanished right after registration"));
        if (node.node() < 1 || node.node() > EventIds.MAX_NODE) {
            throw new SQLException("shard has no node id left for " + name + " (got " + node.node() + ", max "
                    + EventIds.MAX_NODE + ")");
        }
        return node;
    }

    /**
     * Registers an import node ({@link ServerNode#imported()}: {@code last_seen = 0}) under a name no
     * server has. A plain insert, never an upsert: when the name is taken, by a row that was there before
     * or by one another server inserted at the same moment, the unique key rejects it and the result is
     * empty, on every engine (an insert's update count is not "rows inserted" on MySQL, so it is not
     * read). The name is looked up first, so a taken name never consumes an identity value.
     *
     * @param connection a connection in auto-commit mode
     * @param sql        the shard's SQL context
     * @param name       the node's name
     * @param scope      the world scope its events are stored under
     * @return the new node, or empty when a row with that name exists
     * @throws SQLException when a statement fails or the shard has no node id left
     */
    public @NotNull Optional<ServerNode> registerImport(@NotNull Connection connection, @NotNull Sql sql,
            @NotNull String name, @NotNull String scope) throws SQLException {
        if (byName(connection, sql, name).isPresent()) {
            return Optional.empty();
        }
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO " + sql.tables().server()
                + " (" + String.join(", ", COLUMNS) + ") VALUES (?, ?, ?)")) {
            statement.setString(1, name);
            statement.setString(2, scope);
            statement.setLong(3, 0);
            statement.executeUpdate();
        } catch (SQLException e) {
            if (byName(connection, sql, name).isPresent()) {
                // Another server took the name between the lookup and the insert.
                return Optional.empty();
            }
            throw e;
        }
        ServerNode node = byName(connection, sql, name)
                .orElseThrow(() -> new SQLException("import node " + name + " vanished right after registration"));
        if (node.node() < 1 || node.node() > EventIds.MAX_NODE) {
            throw new SQLException("shard has no node id left for " + name + " (got " + node.node() + ", max "
                    + EventIds.MAX_NODE + ")");
        }
        return Optional.of(node);
    }

    /**
     * Records a heartbeat.
     *
     * @param connection a connection
     * @param sql        the shard's SQL context
     * @param name       the server name
     * @param now        the current time
     * @throws SQLException when the statement fails
     */
    public void heartbeat(@NotNull Connection connection, @NotNull Sql sql, @NotNull String name, long now)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE " + sql.tables().server() + " SET last_seen = ? WHERE name = ?")) {
            statement.setLong(1, now);
            statement.setString(2, name);
            statement.executeUpdate();
        }
    }

    /**
     * A server by name.
     *
     * @param connection a connection
     * @param sql        the shard's SQL context
     * @param name       the server name
     * @return the node, or empty when no server has that name
     * @throws SQLException when the query fails
     */
    public @NotNull Optional<ServerNode> byName(@NotNull Connection connection, @NotNull Sql sql,
            @NotNull String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, name, scope, last_seen FROM " + sql.tables().server() + " WHERE name = ?")) {
            statement.setString(1, name);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(read(rows)) : Optional.empty();
            }
        }
    }

    /**
     * Every server of the shard.
     *
     * @param connection a connection
     * @param sql        the shard's SQL context
     * @return the servers, by node id
     * @throws SQLException when the query fails
     */
    public @NotNull List<ServerNode> all(@NotNull Connection connection, @NotNull Sql sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, name, scope, last_seen FROM " + sql.tables().server() + " ORDER BY id");
             ResultSet rows = statement.executeQuery()) {
            List<ServerNode> servers = new ArrayList<>();
            while (rows.next()) {
                servers.add(read(rows));
            }
            return servers;
        }
    }

    private static @NotNull ServerNode read(@NotNull ResultSet rows) throws SQLException {
        return new ServerNode(rows.getInt(1), rows.getString(2), rows.getString(3), rows.getLong(4));
    }
}
