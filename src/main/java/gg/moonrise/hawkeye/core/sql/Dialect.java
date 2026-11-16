package gg.moonrise.hawkeye.core.sql;

import gg.moonrise.hawkeye.config.file.StorageType;
import java.util.List;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;

/**
 * The SQL differences between the supported engines. Everything else Hawkeye runs is portable SQL;
 * this enum is the only place that branches on the engine (the schema DDL lives in
 * {@link Migrations}).
 */
public enum Dialect {

    /** SQLite 3.35+. */
    SQLITE,
    /** MySQL 8+ and MariaDB 10.6+. */
    MYSQL,
    /** PostgreSQL 13+. */
    POSTGRESQL;

    /**
     * The dialect of a storage type.
     *
     * @param type the configured engine
     * @return its dialect
     */
    public static @NotNull Dialect of(@NotNull StorageType type) {
        return switch (type) {
            case SQLITE -> SQLITE;
            case MYSQL, MARIADB -> MYSQL;
            case POSTGRESQL -> POSTGRESQL;
        };
    }

    /**
     * A multi-row insert that skips rows whose unique key already exists, so a retried batch is a
     * no-op. MySQL uses a self-assignment on duplicate key rather than {@code INSERT IGNORE}, which
     * would also hide truncation and other errors.
     *
     * @param table   the table
     * @param columns the columns, the first of which is unique
     * @param rows    how many rows of placeholders
     * @return the statement
     */
    public @NotNull String insertIgnoringDuplicates(@NotNull String table, @NotNull List<String> columns, int rows) {
        String insert = insert(table, columns, rows);
        return switch (this) {
            case SQLITE, POSTGRESQL -> insert + " ON CONFLICT DO NOTHING";
            case MYSQL -> insert + " ON DUPLICATE KEY UPDATE " + columns.getFirst() + " = " + columns.getFirst();
        };
    }

    /**
     * A multi-row insert that updates some columns of rows whose unique key already exists.
     *
     * @param table    the table
     * @param columns  the inserted columns
     * @param conflict the unique column
     * @param updated  the columns overwritten on conflict
     * @param rows     how many rows of placeholders
     * @return the statement
     */
    public @NotNull String upsert(@NotNull String table, @NotNull List<String> columns, @NotNull String conflict,
            @NotNull List<String> updated, int rows) {
        String insert = insert(table, columns, rows);
        return switch (this) {
            case SQLITE, POSTGRESQL -> insert + " ON CONFLICT (" + conflict + ") DO UPDATE SET "
                    + updated.stream().map(column -> column + " = excluded." + column).collect(Collectors.joining(", "));
            case MYSQL -> insert + " ON DUPLICATE KEY UPDATE "
                    + updated.stream().map(column -> column + " = VALUES(" + column + ")")
                    .collect(Collectors.joining(", "));
        };
    }

    /**
     * {@code count} comma-separated placeholders.
     *
     * @param count how many
     * @return e.g. {@code ?, ?, ?}
     */
    public static @NotNull String placeholders(int count) {
        if (count < 1) {
            throw new IllegalArgumentException("at least one placeholder");
        }
        return "?" + ", ?".repeat(count - 1);
    }

    private static @NotNull String insert(@NotNull String table, @NotNull List<String> columns, int rows) {
        if (rows < 1) {
            throw new IllegalArgumentException("at least one row");
        }
        String row = "(" + placeholders(columns.size()) + ")";
        return "INSERT INTO " + table + " (" + String.join(", ", columns) + ") VALUES " + row + (", " + row)
                .repeat(rows - 1);
    }
}
