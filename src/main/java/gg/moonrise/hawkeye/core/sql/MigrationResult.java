package gg.moonrise.hawkeye.core.sql;

/** Outcome of {@link Migrations#apply}. */
public sealed interface MigrationResult {

    /**
     * The database already had the current schema.
     *
     * @param version the schema version
     */
    record UpToDate(int version) implements MigrationResult {
    }

    /**
     * The schema was created or upgraded.
     *
     * @param from the version before (0 for a fresh database)
     * @param to   the version now
     */
    record Migrated(int from, int to) implements MigrationResult {
    }

    /**
     * A newer Hawkeye has upgraded this database; this build refuses to write to it.
     *
     * @param version the database's schema version
     */
    record DatabaseAhead(int version) implements MigrationResult {
    }
}
