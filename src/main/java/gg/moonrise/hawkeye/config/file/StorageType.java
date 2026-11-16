package gg.moonrise.hawkeye.config.file;

/** The database engine of a shard. */
public enum StorageType {

    /** An embedded SQLite file: one server, no setup. */
    SQLITE(0),
    /** MySQL 8+, through the MariaDB driver. */
    MYSQL(3306),
    /** MariaDB 10.6+. */
    MARIADB(3306),
    /** PostgreSQL 13+. */
    POSTGRESQL(5432);

    private final int defaultPort;

    StorageType(int defaultPort) {
        this.defaultPort = defaultPort;
    }

    /**
     * The port used when the configuration sets none.
     *
     * @return the engine's standard port, 0 for SQLite
     */
    public int defaultPort() {
        return defaultPort;
    }
}
