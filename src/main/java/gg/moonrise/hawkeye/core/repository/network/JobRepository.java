package gg.moonrise.hawkeye.core.repository.network;

import gg.moonrise.hawkeye.core.sql.Dialect;
import gg.moonrise.hawkeye.core.sql.Sql;
import gg.moonrise.hawkeye.entity.job.JobKind;
import gg.moonrise.hawkeye.entity.job.JobStatus;
import gg.moonrise.hawkeye.entity.job.NetworkJob;
import gg.moonrise.moss.spring.SpringComponent;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;

/**
 * The {@code job} table: network rollbacks and restores. A job is claimed with a conditional update
 * ({@code WHERE status = PENDING}), so exactly one attempt applies it even when the bus and the poller
 * notice it at the same time.
 */
@SpringComponent
public final class JobRepository {

    private static final String COLUMNS = "id, target, kind, status, issuer_server, issuer, payload, result, created, "
            + "updated";

    /**
     * Writes a pending job.
     *
     * @param connection   a connection to the target's shard
     * @param sql          the shard's SQL context
     * @param target       the target server name
     * @param kind         rollback or restore
     * @param issuerServer the issuing server's name
     * @param issuer       who asked
     * @param query        the encoded query
     * @param now          the current time
     * @return the new job's id
     * @throws SQLException when the insert fails
     */
    public long insert(@NotNull Connection connection, @NotNull Sql sql, @NotNull String target, @NotNull JobKind kind,
            @NotNull String issuerServer, @NotNull String issuer, @NotNull String query, long now) throws SQLException {
        String insert = "INSERT INTO " + sql.tables().job() + " (target, kind, status, issuer_server, issuer, payload, "
                + "created, updated) VALUES (" + Dialect.placeholders(8) + ")";
        try (PreparedStatement statement = connection.prepareStatement(insert, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, target);
            statement.setInt(2, kind.code());
            statement.setInt(3, JobStatus.PENDING.code());
            statement.setString(4, issuerServer);
            statement.setString(5, issuer);
            statement.setString(6, query);
            statement.setLong(7, now);
            statement.setLong(8, now);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("the job insert returned no id");
                }
                return keys.getLong(1);
            }
        }
    }

    /**
     * The pending jobs addressed to a server, oldest first.
     *
     * @param connection a connection to the server's home shard
     * @param sql        the shard's SQL context
     * @param target     the server name
     * @return the jobs
     * @throws SQLException when the query fails
     */
    public @NotNull List<NetworkJob> pending(@NotNull Connection connection, @NotNull Sql sql, @NotNull String target)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT " + COLUMNS + " FROM "
                + sql.tables().job() + " WHERE target = ? AND status = ? ORDER BY id")) {
            statement.setString(1, target);
            statement.setInt(2, JobStatus.PENDING.code());
            return read(statement);
        }
    }

    /**
     * Jobs by id.
     *
     * @param connection a connection to the jobs' shard
     * @param sql        the shard's SQL context
     * @param ids        job ids
     * @return the jobs that exist
     * @throws SQLException when the query fails
     */
    public @NotNull List<NetworkJob> byIds(@NotNull Connection connection, @NotNull Sql sql,
            @NotNull Collection<Long> ids) throws SQLException {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Long> values = List.copyOf(ids);
        try (PreparedStatement statement = connection.prepareStatement("SELECT " + COLUMNS + " FROM "
                + sql.tables().job() + " WHERE id IN (" + Dialect.placeholders(values.size()) + ")")) {
            for (int i = 0; i < values.size(); i++) {
                statement.setLong(i + 1, values.get(i));
            }
            return read(statement);
        }
    }

    /**
     * Claims a pending job.
     *
     * @param connection a connection
     * @param sql        the shard's SQL context
     * @param id         the job id
     * @param now        the current time
     * @return {@code true} when this call claimed it, {@code false} when it was no longer pending
     * @throws SQLException when the update fails
     */
    public boolean claim(@NotNull Connection connection, @NotNull Sql sql, long id, long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE " + sql.tables().job()
                + " SET status = ?, updated = ? WHERE id = ? AND status = ?")) {
            statement.setInt(1, JobStatus.RUNNING.code());
            statement.setLong(2, now);
            statement.setLong(3, id);
            statement.setInt(4, JobStatus.PENDING.code());
            return statement.executeUpdate() == 1;
        }
    }

    /**
     * Records a job's end.
     *
     * @param connection a connection
     * @param sql        the shard's SQL context
     * @param id         the job id
     * @param status     {@link JobStatus#DONE} or {@link JobStatus#FAILED}
     * @param result     the counts or the reason
     * @param now        the current time
     * @throws SQLException when the update fails
     */
    public void finish(@NotNull Connection connection, @NotNull Sql sql, long id, @NotNull JobStatus status,
            @NotNull String result, long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE " + sql.tables().job()
                + " SET status = ?, result = ?, updated = ? WHERE id = ?")) {
            statement.setInt(1, status.code());
            statement.setString(2, result);
            statement.setLong(3, now);
            statement.setLong(4, id);
            statement.executeUpdate();
        }
    }

    /**
     * Marks jobs stuck in {@link JobStatus#RUNNING} since before a time as failed (their server stopped
     * while applying them).
     *
     * @param connection a connection
     * @param sql        the shard's SQL context
     * @param target     the server whose jobs to check
     * @param before     jobs not updated since this time are abandoned
     * @param reason     the result to store
     * @param now        the current time
     * @return the number of jobs marked
     * @throws SQLException when the update fails
     */
    public int abandon(@NotNull Connection connection, @NotNull Sql sql, @NotNull String target, long before,
            @NotNull String reason, long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE " + sql.tables().job()
                + " SET status = ?, result = ?, updated = ? WHERE target = ? AND status = ? AND updated < ?")) {
            statement.setInt(1, JobStatus.FAILED.code());
            statement.setString(2, reason);
            statement.setLong(3, now);
            statement.setString(4, target);
            statement.setInt(5, JobStatus.RUNNING.code());
            statement.setLong(6, before);
            return statement.executeUpdate();
        }
    }

    /**
     * Deletes finished jobs older than a time.
     *
     * @param connection a connection
     * @param sql        the shard's SQL context
     * @param before     finished jobs last updated before this are deleted
     * @return the number deleted
     * @throws SQLException when the delete fails
     */
    public int prune(@NotNull Connection connection, @NotNull Sql sql, long before) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM " + sql.tables().job()
                + " WHERE status IN (?, ?) AND updated < ?")) {
            statement.setInt(1, JobStatus.DONE.code());
            statement.setInt(2, JobStatus.FAILED.code());
            statement.setLong(3, before);
            return statement.executeUpdate();
        }
    }

    private static @NotNull List<NetworkJob> read(@NotNull PreparedStatement statement) throws SQLException {
        List<NetworkJob> jobs = new ArrayList<>();
        try (ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                Optional<JobKind> kind = JobKind.byCode(rows.getInt(3));
                Optional<JobStatus> status = JobStatus.byCode(rows.getInt(4));
                if (kind.isEmpty() || status.isEmpty()) {
                    continue;
                }
                jobs.add(new NetworkJob(rows.getLong(1), rows.getString(2), kind.get(), status.get(), rows.getString(5),
                        rows.getString(6), rows.getString(7), rows.getString(8), rows.getLong(9), rows.getLong(10)));
            }
        }
        return jobs;
    }
}
