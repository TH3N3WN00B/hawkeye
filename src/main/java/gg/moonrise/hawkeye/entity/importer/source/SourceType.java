package gg.moonrise.hawkeye.entity.importer.source;

/** The database engines CoreProtect (upstream 25.x) stores its history in. */
public enum SourceType {
    /** A {@code database.db} file in the CoreProtect folder. */
    SQLITE,
    /** A MySQL or MariaDB server, read through the MariaDB driver. */
    MYSQL,
    /** A {@code database.duckdb} file in the CoreProtect folder. */
    DUCKDB,
    /** A ClickHouse server, read over HTTP through its compatibility views. */
    CLICKHOUSE
}
