package gg.moonrise.hawkeye.core.repository.coreprotect.driver;

import java.util.Locale;
import org.jetbrains.annotations.NotNull;

/**
 * A JDBC driver jar that is fetched on demand instead of being loaded with the plugin: its Maven Central
 * coordinates, the SHA-256 of the exact file, and the {@link java.sql.Driver} class it ships.
 *
 * @param group       the Maven group id
 * @param artifact    the Maven artifact id
 * @param version     the version
 * @param classifier  the classifier, or empty
 * @param sha256      the file's SHA-256, lower-case hex
 * @param driverClass the driver class name
 */
public record DriverArtifact(
        @NotNull String group,
        @NotNull String artifact,
        @NotNull String version,
        @NotNull String classifier,
        @NotNull String sha256,
        @NotNull String driverClass) {

    /** DuckDB's driver (about 80 MB: it carries the native engine for every platform). */
    public static final DriverArtifact DUCKDB = new DriverArtifact("org.duckdb", "duckdb_jdbc", "1.4.5.0", "",
            "5f11500c4fe8af47cfb15b36bb92e22597ad2109556c1564823e2db1a210072e", "org.duckdb.DuckDBDriver");

    /**
     * ClickHouse's driver with its dependencies shaded in (about 12 MB). The jar registers both
     * {@code com.clickhouse.jdbc.Driver} (the JDBC v2 driver) and the older {@code ClickHouseDriver}
     * wrapper; the v2 driver is used directly.
     */
    public static final DriverArtifact CLICKHOUSE = new DriverArtifact("com.clickhouse", "clickhouse-jdbc", "0.10.0",
            "all", "d83736e24306e11929b0cf81b3306ae903c6f51c735c509ad5680c88640d99ce", "com.clickhouse.jdbc.Driver");

    /**
     * Normalizes the checksum.
     */
    public DriverArtifact {
        sha256 = sha256.toLowerCase(Locale.ROOT);
    }

    /**
     * The jar's file name, the same in the repository and in the drivers folder.
     *
     * @return e.g. {@code clickhouse-jdbc-0.10.0-all.jar}
     */
    public @NotNull String fileName() {
        return artifact + "-" + version + (classifier.isEmpty() ? "" : "-" + classifier) + ".jar";
    }

    /**
     * The jar's path below a Maven repository's root.
     *
     * @return e.g. {@code com/clickhouse/clickhouse-jdbc/0.10.0/clickhouse-jdbc-0.10.0-all.jar}
     */
    public @NotNull String repositoryPath() {
        return group.replace('.', '/') + "/" + artifact + "/" + version + "/" + fileName();
    }
}
