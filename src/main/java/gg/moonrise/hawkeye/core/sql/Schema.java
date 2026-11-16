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
    public static final int CURRENT = 4;

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
        return switch (version) {
            case 1 -> switch (dialect) {
                case SQLITE -> sqliteV1(tables);
                case MYSQL -> mysqlV1(tables);
                case POSTGRESQL -> postgresV1(tables);
            };
            case 2 -> switch (dialect) {
                case SQLITE -> sqliteV2(tables);
                case MYSQL -> mysqlV2(tables);
                case POSTGRESQL -> postgresV2(tables);
            };
            case 3 -> switch (dialect) {
                case SQLITE -> sqliteV3(tables);
                case MYSQL -> mysqlV3(tables);
                case POSTGRESQL -> postgresV3(tables);
            };
            case 4 -> switch (dialect) {
                case SQLITE -> sqliteV4(tables);
                case MYSQL -> mysqlV4(tables);
                case POSTGRESQL -> postgresV4(tables);
            };
            default -> throw new IllegalArgumentException("unknown schema version " + version);
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

    /**
     * Version 2: the development archive (packed per-region segments) and its actor index. Version 3
     * drops both again; they are kept here so the version history reads as it ran.
     */
    private static @NotNull List<String> sqliteV2(@NotNull Tables t) {
        return List.of(
                "CREATE TABLE IF NOT EXISTS " + t.segment() + " (id INTEGER PRIMARY KEY, world INTEGER NOT NULL, "
                        + "region INTEGER NOT NULL, first_id INTEGER NOT NULL, last_id INTEGER NOT NULL, "
                        + "events INTEGER NOT NULL, body BLOB NOT NULL)",
                "CREATE INDEX IF NOT EXISTS " + t.segment() + "_region ON " + t.segment() + " (world, region, last_id)",
                "CREATE INDEX IF NOT EXISTS " + t.segment() + "_last ON " + t.segment() + " (last_id)",
                "CREATE TABLE IF NOT EXISTS " + t.legacySegmentActor() + " (actor INTEGER NOT NULL, "
                        + "segment INTEGER NOT NULL, PRIMARY KEY (actor, segment)) WITHOUT ROWID");
    }

    private static @NotNull List<String> mysqlV2(@NotNull Tables t) {
        String options = " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin";
        return List.of(
                "CREATE TABLE IF NOT EXISTS " + t.segment() + " (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, "
                        + "world INT NOT NULL, region BIGINT NOT NULL, first_id BIGINT NOT NULL, "
                        + "last_id BIGINT NOT NULL, events INT NOT NULL, body MEDIUMBLOB NOT NULL, "
                        + "KEY " + t.segment() + "_region (world, region, last_id), "
                        + "KEY " + t.segment() + "_last (last_id))" + options,
                "CREATE TABLE IF NOT EXISTS " + t.legacySegmentActor() + " (actor INT NOT NULL, segment BIGINT NOT NULL, "
                        + "PRIMARY KEY (actor, segment))" + options);
    }

    private static @NotNull List<String> postgresV2(@NotNull Tables t) {
        return List.of(
                "CREATE TABLE IF NOT EXISTS " + t.segment() + " (id BIGINT GENERATED BY DEFAULT AS IDENTITY "
                        + "PRIMARY KEY, world INTEGER NOT NULL, region BIGINT NOT NULL, first_id BIGINT NOT NULL, "
                        + "last_id BIGINT NOT NULL, events INTEGER NOT NULL, body BYTEA NOT NULL)",
                "CREATE INDEX IF NOT EXISTS " + t.segment() + "_region ON " + t.segment() + " (world, region, last_id)",
                "CREATE INDEX IF NOT EXISTS " + t.segment() + "_last ON " + t.segment() + " (last_id)",
                "CREATE TABLE IF NOT EXISTS " + t.legacySegmentActor() + " (actor INTEGER NOT NULL, segment BIGINT NOT NULL, "
                        + "PRIMARY KEY (actor, segment))");
    }

    /**
     * Version 3: archive format 2. Drops the development archive (its segments are discarded, never
     * converted) and creates {@code segment} (immutable header and body of up to a million events) and
     * {@code segment_flag} (the rolled-back rows of a segment, as delta-coded ordinals).
     */
    private static @NotNull List<String> sqliteV3(@NotNull Tables t) {
        return List.of(
                "DROP TABLE IF EXISTS " + t.legacySegmentActor(),
                "DROP TABLE IF EXISTS " + t.segment(),
                "CREATE TABLE IF NOT EXISTS " + t.segment() + " (id INTEGER PRIMARY KEY AUTOINCREMENT, "
                        + "first_id INTEGER NOT NULL, last_id INTEGER NOT NULL, events INTEGER NOT NULL, "
                        + "header BLOB NOT NULL, body BLOB NOT NULL)",
                "CREATE INDEX IF NOT EXISTS " + t.segment() + "_last ON " + t.segment() + " (last_id)",
                "CREATE INDEX IF NOT EXISTS " + t.segment() + "_first ON " + t.segment() + " (first_id)",
                "CREATE TABLE IF NOT EXISTS " + t.segmentFlag() + " (segment INTEGER PRIMARY KEY, "
                        + "ordinals BLOB NOT NULL)");
    }

    private static @NotNull List<String> mysqlV3(@NotNull Tables t) {
        String options = " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin";
        return List.of(
                "DROP TABLE IF EXISTS " + t.legacySegmentActor(),
                "DROP TABLE IF EXISTS " + t.segment(),
                "CREATE TABLE IF NOT EXISTS " + t.segment() + " (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, "
                        + "first_id BIGINT NOT NULL, last_id BIGINT NOT NULL, events INT NOT NULL, "
                        + "header MEDIUMBLOB NOT NULL, body LONGBLOB NOT NULL, "
                        + "KEY " + t.segment() + "_last (last_id), "
                        + "KEY " + t.segment() + "_first (first_id))" + options,
                "CREATE TABLE IF NOT EXISTS " + t.segmentFlag() + " (segment BIGINT NOT NULL PRIMARY KEY, "
                        + "ordinals MEDIUMBLOB NOT NULL)" + options);
    }

    private static @NotNull List<String> postgresV3(@NotNull Tables t) {
        return List.of(
                "DROP TABLE IF EXISTS " + t.legacySegmentActor(),
                "DROP TABLE IF EXISTS " + t.segment(),
                "CREATE TABLE IF NOT EXISTS " + t.segment() + " (id BIGINT GENERATED BY DEFAULT AS IDENTITY "
                        + "PRIMARY KEY, first_id BIGINT NOT NULL, last_id BIGINT NOT NULL, events INTEGER NOT NULL, "
                        + "header BYTEA NOT NULL, body BYTEA NOT NULL)",
                // The frames are compressed already; stored out of line uncompressed, substr reads only the
                // TOAST chunks of the requested range.
                "ALTER TABLE " + t.segment() + " ALTER COLUMN body SET STORAGE EXTERNAL",
                "CREATE INDEX IF NOT EXISTS " + t.segment() + "_last ON " + t.segment() + " (last_id)",
                "CREATE INDEX IF NOT EXISTS " + t.segment() + "_first ON " + t.segment() + " (first_id)",
                "CREATE TABLE IF NOT EXISTS " + t.segmentFlag() + " (segment BIGINT PRIMARY KEY, "
                        + "ordinals BYTEA NOT NULL)");
    }

    /**
     * Version 4: the place index ({@code place_region}, {@code place_chunk}, {@code place_state}). Every
     * segment that exists already is below {@code indexed_from}; the archiver indexes them afterwards.
     */
    private static @NotNull List<String> sqliteV4(@NotNull Tables t) {
        return List.of(
                "CREATE TABLE IF NOT EXISTS " + t.placeRegion() + " (world INTEGER NOT NULL, region INTEGER NOT NULL, "
                        + "newest INTEGER NOT NULL, body BLOB NOT NULL, PRIMARY KEY (world, region)) WITHOUT ROWID",
                "CREATE INDEX IF NOT EXISTS " + t.placeRegion() + "_newest ON " + t.placeRegion() + " (newest)",
                "CREATE TABLE IF NOT EXISTS " + t.placeChunk() + " (world INTEGER NOT NULL, chunk INTEGER NOT NULL, "
                        + "newest INTEGER NOT NULL, body BLOB NOT NULL, PRIMARY KEY (world, chunk)) WITHOUT ROWID",
                "CREATE INDEX IF NOT EXISTS " + t.placeChunk() + "_newest ON " + t.placeChunk() + " (newest)",
                "CREATE TABLE IF NOT EXISTS " + t.placeState() + " (id INTEGER PRIMARY KEY, "
                        + "indexed_from INTEGER NOT NULL)",
                "INSERT OR IGNORE INTO " + t.placeState() + " (id, indexed_from) SELECT 1, COALESCE(MAX(id), 0) + 1 FROM "
                        + t.segment());
    }

    private static @NotNull List<String> mysqlV4(@NotNull Tables t) {
        String options = " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin";
        return List.of(
                "CREATE TABLE IF NOT EXISTS " + t.placeRegion() + " (world INT NOT NULL, region BIGINT NOT NULL, "
                        + "newest BIGINT NOT NULL, body MEDIUMBLOB NOT NULL, PRIMARY KEY (world, region), "
                        + "KEY " + t.placeRegion() + "_newest (newest))" + options,
                "CREATE TABLE IF NOT EXISTS " + t.placeChunk() + " (world INT NOT NULL, chunk BIGINT NOT NULL, "
                        + "newest BIGINT NOT NULL, body MEDIUMBLOB NOT NULL, PRIMARY KEY (world, chunk), "
                        + "KEY " + t.placeChunk() + "_newest (newest))" + options,
                "CREATE TABLE IF NOT EXISTS " + t.placeState() + " (id INT NOT NULL PRIMARY KEY, "
                        + "indexed_from BIGINT NOT NULL)" + options,
                "INSERT INTO " + t.placeState() + " (id, indexed_from) SELECT 1, COALESCE(MAX(id), 0) + 1 FROM "
                        + t.segment() + " ON DUPLICATE KEY UPDATE indexed_from = " + t.placeState() + ".indexed_from");
    }

    private static @NotNull List<String> postgresV4(@NotNull Tables t) {
        return List.of(
                "CREATE TABLE IF NOT EXISTS " + t.placeRegion() + " (world INTEGER NOT NULL, region BIGINT NOT NULL, "
                        + "newest BIGINT NOT NULL, body BYTEA NOT NULL, PRIMARY KEY (world, region))",
                "CREATE INDEX IF NOT EXISTS " + t.placeRegion() + "_newest ON " + t.placeRegion() + " (newest)",
                "CREATE TABLE IF NOT EXISTS " + t.placeChunk() + " (world INTEGER NOT NULL, chunk BIGINT NOT NULL, "
                        + "newest BIGINT NOT NULL, body BYTEA NOT NULL, PRIMARY KEY (world, chunk))",
                "CREATE INDEX IF NOT EXISTS " + t.placeChunk() + "_newest ON " + t.placeChunk() + " (newest)",
                "CREATE TABLE IF NOT EXISTS " + t.placeState() + " (id INTEGER PRIMARY KEY, "
                        + "indexed_from BIGINT NOT NULL)",
                "INSERT INTO " + t.placeState() + " (id, indexed_from) SELECT 1, COALESCE(MAX(id), 0) + 1 FROM "
                        + t.segment() + " ON CONFLICT (id) DO NOTHING");
    }
}
