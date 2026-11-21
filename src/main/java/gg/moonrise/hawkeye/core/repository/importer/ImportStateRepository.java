package gg.moonrise.hawkeye.core.repository.importer;

import gg.moonrise.hawkeye.core.sql.Sql;
import gg.moonrise.hawkeye.entity.importer.ImportState;
import gg.moonrise.hawkeye.util.codec.binary.CodecException;
import gg.moonrise.hawkeye.util.codec.ImportStateCodec;
import gg.moonrise.moss.spring.SpringComponent;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;

/**
 * The {@code import_state} table: one checkpoint per imported source, its text in {@code state}
 * ({@link ImportStateCodec}) and its {@code revision} in a column of its own, which every update
 * compares and raises ({@link #compareAndSet}).
 */
@SpringComponent
public final class ImportStateRepository {

    /** The longest source key the table stores (the MySQL key length limit of a utf8mb4 column). */
    public static final int MAX_SOURCE_LENGTH = 191;

    /**
     * A source's checkpoint.
     *
     * @param connection a connection
     * @param sql        the shard's SQL context
     * @param source     the source key
     * @return the state at its stored revision, or empty when the source was never imported
     * @throws SQLException when the query fails or the stored text cannot be decoded
     */
    public @NotNull Optional<ImportState> find(@NotNull Connection connection, @NotNull Sql sql,
            @NotNull String source) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT revision, state, updated FROM "
                + sql.tables().importState() + " WHERE source = ?")) {
            statement.setString(1, checked(source));
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                try {
                    return Optional.of(ImportStateCodec.decode(rows.getString(2), rows.getLong(1), rows.getLong(3)));
                } catch (CodecException e) {
                    throw new SQLException("the import state of " + source + " cannot be read: " + e.getMessage(), e);
                }
            }
        }
    }

    /**
     * Stores the first checkpoint of a source, at the state's revision. A plain insert: when another
     * server stored one first, the primary key rejects it and the result is {@code false} on every
     * engine (an insert's update count is not read; MySQL reports matched rows).
     *
     * @param connection a connection in auto-commit mode
     * @param sql        the shard's SQL context
     * @param source     the source key
     * @param state      the state
     * @return {@code true} when this call stored it, {@code false} when the source already has a row
     * @throws SQLException when the statement fails for another reason
     */
    public boolean insert(@NotNull Connection connection, @NotNull Sql sql, @NotNull String source,
            @NotNull ImportState state) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO " + sql.tables().importState()
                + " (source, revision, state, updated) VALUES (?, ?, ?, ?)")) {
            statement.setString(1, checked(source));
            statement.setLong(2, state.revision());
            statement.setString(3, ImportStateCodec.encode(state));
            statement.setLong(4, state.updatedMillis());
            statement.executeUpdate();
            return true;
        } catch (SQLException e) {
            if (exists(connection, sql, source)) {
                return false;
            }
            throw e;
        }
    }

    /**
     * Replaces a checkpoint when it is still at the revision the caller holds, raising the revision by
     * one. The revision always changes, so the matched and the changed row counts agree on every engine.
     *
     * @param connection a connection, normally inside the transaction that writes the segment
     * @param sql        the shard's SQL context
     * @param source     the source key
     * @param next       the new state; its {@code revision} is the one the caller holds
     * @return {@code true} when the row was at that revision and is replaced, {@code false} when another
     *         run moved it
     * @throws SQLException when the statement fails
     */
    public boolean compareAndSet(@NotNull Connection connection, @NotNull Sql sql, @NotNull String source,
            @NotNull ImportState next) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE " + sql.tables().importState()
                + " SET revision = revision + 1, state = ?, updated = ? WHERE source = ? AND revision = ?")) {
            statement.setString(1, ImportStateCodec.encode(next));
            statement.setLong(2, next.updatedMillis());
            statement.setString(3, checked(source));
            statement.setLong(4, next.revision());
            return statement.executeUpdate() == 1;
        }
    }

    private static boolean exists(@NotNull Connection connection, @NotNull Sql sql, @NotNull String source)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM " + sql.tables().importState()
                + " WHERE source = ?")) {
            statement.setString(1, source);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private static @NotNull String checked(@NotNull String source) throws SQLException {
        if (source.isEmpty() || source.length() > MAX_SOURCE_LENGTH) {
            throw new SQLException("an import source key has 1 to " + MAX_SOURCE_LENGTH + " characters, not "
                    + source.length());
        }
        return source;
    }
}
