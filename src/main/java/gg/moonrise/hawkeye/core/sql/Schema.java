package gg.moonrise.hawkeye.core.sql;

import java.util.List;
import org.jetbrains.annotations.NotNull;

/**
 * The DDL of every schema version, written out per engine so each statement can be read and reviewed
 * as it runs (docs/architecture.md, "Tables"). Shipped versions never change; a schema change is a new
 * version with its own statements. Every statement is idempotent, so two servers migrating the same
 * fresh database at once both succeed.
 */
public final class Schema {

    /** The schema version this build writes. */
    public static final int CURRENT = 1;

    private Schema() {
    }

    /**
     * The statements that bring a database from {@code version - 1} to {@code version}.
     *
     * @param version the target version, 1 to {@link #CURRENT}
     * @param dialect the engine
     * @param tables  the table names
     * @return the statements, in order
     */
    public static @NotNull List<String> statements(int version, @NotNull Dialect dialect, @NotNull Tables tables) {
        if (version != 1) {
            throw new IllegalArgumentException("unknown schema version " + version);
        }
        return switch (dialect) {
            case SQLITE -> sqliteV1(tables);
            case MYSQL -> mysqlV1(tables);
            case POSTGRESQL -> postgresV1(tables);
        };
    }

    private static @NotNull List<String> sqliteV1(@NotNull Tables t) {
        return List.of(
                "CREATE TABLE IF NOT EXISTS " + t.server() + " (id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE, "
                        + "scope TEXT NOT NULL, last_seen INTEGER NOT NULL)",
                "CREATE TABLE IF NOT EXISTS " + t.world() + " (id INTEGER PRIMARY KEY, scope TEXT NOT NULL, "
                        + "name TEXT NOT NULL, UNIQUE (scope, name))",
                "CREATE TABLE IF NOT EXISTS " + t.actor() + " (id INTEGER PRIMARY KEY, kind INTEGER NOT NULL, "
                        + "actor_key TEXT NOT NULL UNIQUE, name TEXT NOT NULL, name_lower TEXT NOT NULL)",
                "CREATE INDEX IF NOT EXISTS " + t.actor() + "_name ON " + t.actor() + " (name_lower)",
                "CREATE TABLE IF NOT EXISTS " + t.type() + " (id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE)",
                "CREATE TABLE IF NOT EXISTS " + t.event() + " (id INTEGER PRIMARY KEY, world INTEGER NOT NULL, "
                        + "pos INTEGER NOT NULL, actor INTEGER NOT NULL, action INTEGER NOT NULL, "
                        + "subject INTEGER NOT NULL, secondary INTEGER, amount INTEGER, "
                        + "flags INTEGER NOT NULL DEFAULT 0, data BLOB)",
                "CREATE INDEX IF NOT EXISTS " + t.event() + "_pos ON " + t.event() + " (world, pos)",
                "CREATE INDEX IF NOT EXISTS " + t.event() + "_actor ON " + t.event() + " (actor)",
                "CREATE TABLE IF NOT EXISTS " + t.job() + " (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                        + "target TEXT NOT NULL, kind INTEGER NOT NULL, status INTEGER NOT NULL, "
                        + "issuer_server TEXT NOT NULL, issuer TEXT NOT NULL, payload TEXT NOT NULL, result TEXT, "
                        + "created INTEGER NOT NULL, updated INTEGER NOT NULL)",
                "CREATE INDEX IF NOT EXISTS " + t.job() + "_target ON " + t.job() + " (target, status)",
                "CREATE TABLE IF NOT EXISTS " + t.lease() + " (name TEXT PRIMARY KEY, holder TEXT NOT NULL, "
                        + "expires INTEGER NOT NULL)");
    }

    private static @NotNull List<String> mysqlV1(@NotNull Tables t) {
        String options = " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin";
        return List.of(
                "CREATE TABLE IF NOT EXISTS " + t.server() + " (id SMALLINT NOT NULL AUTO_INCREMENT PRIMARY KEY, "
                        + "name VARCHAR(64) NOT NULL, scope VARCHAR(64) NOT NULL, last_seen BIGINT NOT NULL, "
                        + "UNIQUE KEY " + t.server() + "_name (name))" + options,
                "CREATE TABLE IF NOT EXISTS " + t.world() + " (id INT NOT NULL AUTO_INCREMENT PRIMARY KEY, "
                        + "scope VARCHAR(64) NOT NULL, name VARCHAR(255) NOT NULL, "
                        + "UNIQUE KEY " + t.world() + "_key (scope, name))" + options,
                "CREATE TABLE IF NOT EXISTS " + t.actor() + " (id INT NOT NULL AUTO_INCREMENT PRIMARY KEY, "
                        + "kind TINYINT NOT NULL, actor_key VARCHAR(64) NOT NULL, name VARCHAR(64) NOT NULL, "
                        + "name_lower VARCHAR(64) NOT NULL, UNIQUE KEY " + t.actor() + "_key (actor_key), "
                        + "KEY " + t.actor() + "_name (name_lower))" + options,
                "CREATE TABLE IF NOT EXISTS " + t.type() + " (id INT NOT NULL AUTO_INCREMENT PRIMARY KEY, "
                        + "name VARCHAR(512) NOT NULL, UNIQUE KEY " + t.type() + "_name (name))" + options,
                "CREATE TABLE IF NOT EXISTS " + t.event() + " (id BIGINT NOT NULL PRIMARY KEY, world INT NOT NULL, "
                        + "pos BIGINT NOT NULL, actor INT NOT NULL, action TINYINT UNSIGNED NOT NULL, "
                        + "subject INT NOT NULL, secondary INT NULL, amount INT NULL, "
                        + "flags TINYINT UNSIGNED NOT NULL DEFAULT 0, data MEDIUMBLOB NULL, "
                        + "KEY " + t.event() + "_pos (world, pos), KEY " + t.event() + "_actor (actor))" + options,
                "CREATE TABLE IF NOT EXISTS " + t.job() + " (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, "
                        + "target VARCHAR(64) NOT NULL, kind TINYINT NOT NULL, status TINYINT NOT NULL, "
                        + "issuer_server VARCHAR(64) NOT NULL, issuer VARCHAR(64) NOT NULL, payload TEXT NOT NULL, "
                        + "result TEXT NULL, created BIGINT NOT NULL, updated BIGINT NOT NULL, "
                        + "KEY " + t.job() + "_target (target, status))" + options,
                "CREATE TABLE IF NOT EXISTS " + t.lease() + " (name VARCHAR(64) NOT NULL PRIMARY KEY, "
                        + "holder VARCHAR(64) NOT NULL, expires BIGINT NOT NULL)" + options);
    }

    private static @NotNull List<String> postgresV1(@NotNull Tables t) {
        String text = " TEXT COLLATE \"C\"";
        return List.of(
                "CREATE TABLE IF NOT EXISTS " + t.server() + " (id SMALLINT GENERATED BY DEFAULT AS IDENTITY "
                        + "PRIMARY KEY, name" + text + " NOT NULL UNIQUE, scope" + text + " NOT NULL, "
                        + "last_seen BIGINT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS " + t.world() + " (id INTEGER GENERATED BY DEFAULT AS IDENTITY "
                        + "PRIMARY KEY, scope" + text + " NOT NULL, name" + text + " NOT NULL, UNIQUE (scope, name))",
                "CREATE TABLE IF NOT EXISTS " + t.actor() + " (id INTEGER GENERATED BY DEFAULT AS IDENTITY "
                        + "PRIMARY KEY, kind SMALLINT NOT NULL, actor_key" + text + " NOT NULL UNIQUE, "
                        + "name" + text + " NOT NULL, name_lower" + text + " NOT NULL)",
                "CREATE INDEX IF NOT EXISTS " + t.actor() + "_name ON " + t.actor() + " (name_lower)",
                "CREATE TABLE IF NOT EXISTS " + t.type() + " (id INTEGER GENERATED BY DEFAULT AS IDENTITY "
                        + "PRIMARY KEY, name" + text + " NOT NULL UNIQUE)",
                "CREATE TABLE IF NOT EXISTS " + t.event() + " (id BIGINT PRIMARY KEY, world INTEGER NOT NULL, "
                        + "pos BIGINT NOT NULL, actor INTEGER NOT NULL, action SMALLINT NOT NULL, "
                        + "subject INTEGER NOT NULL, secondary INTEGER, amount INTEGER, "
                        + "flags SMALLINT NOT NULL DEFAULT 0, data BYTEA)",
                "CREATE INDEX IF NOT EXISTS " + t.event() + "_pos ON " + t.event() + " (world, pos, id)",
                "CREATE INDEX IF NOT EXISTS " + t.event() + "_actor ON " + t.event() + " (actor, id)",
                "CREATE TABLE IF NOT EXISTS " + t.job() + " (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, "
                        + "target" + text + " NOT NULL, kind SMALLINT NOT NULL, status SMALLINT NOT NULL, "
                        + "issuer_server" + text + " NOT NULL, issuer" + text + " NOT NULL, payload TEXT NOT NULL, "
                        + "result TEXT, created BIGINT NOT NULL, updated BIGINT NOT NULL)",
                "CREATE INDEX IF NOT EXISTS " + t.job() + "_target ON " + t.job() + " (target, status)",
                "CREATE TABLE IF NOT EXISTS " + t.lease() + " (name" + text + " PRIMARY KEY, holder" + text
                        + " NOT NULL, expires BIGINT NOT NULL)");
    }
}
