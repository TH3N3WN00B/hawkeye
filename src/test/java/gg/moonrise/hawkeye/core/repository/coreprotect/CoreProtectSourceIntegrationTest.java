package gg.moonrise.hawkeye.core.repository.coreprotect;

import static gg.moonrise.hawkeye.core.repository.coreprotect.CoreProtectFixture.at;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import gg.moonrise.hawkeye.core.repository.coreprotect.CoreProtectFixture.Layout;
import gg.moonrise.hawkeye.entity.importer.SourceCursor;
import gg.moonrise.hawkeye.entity.importer.SourceRow;
import gg.moonrise.hawkeye.entity.importer.SourceSettings;
import gg.moonrise.hawkeye.entity.importer.SourceTable;
import gg.moonrise.hawkeye.entity.importer.SourceType;
import java.net.URI;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mariadb.jdbc.MariaDbDataSource;

/**
 * The reader scenario ({@link ReaderScenario}) against real CoreProtect engines. Opt-in:
 * <ul>
 *     <li>{@code HAWKEYE_TEST_COREPROTECT_MYSQL=mariadb://user:password@host:port/database} (MySQL or
 *     MariaDB; tables under a random prefix, dropped afterwards);</li>
 *     <li>{@code HAWKEYE_TEST_COREPROTECT_DUCKDB=1} (a temporary DuckDB file; the driver comes from
 *     {@link DriverResolver} with its folder in {@code ~/.cache/hawkeye-ref/drivers}, downloaded once; set
 *     {@code JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=...} to keep the native library and the file off a
 *     RAM-backed {@code /tmp});</li>
 *     <li>{@code HAWKEYE_TEST_COREPROTECT_CLICKHOUSE=http://user:password@host:port/database} (plain tables
 *     with the columns of CoreProtect's compatibility views, under a random prefix, dropped afterwards).</li>
 * </ul>
 * Without them the tests are skipped.
 */
public class CoreProtectSourceIntegrationTest {

    @TempDir
    private Path folder;

    @Test
    public void readsMysql() throws SQLException {
        URI uri = uri("HAWKEYE_TEST_COREPROTECT_MYSQL");
        String[] user = credentials(uri);
        String prefix = prefix();
        MariaDbDataSource writer = new MariaDbDataSource("jdbc:mariadb://" + uri.getHost() + ":" + uri.getPort()
                + uri.getPath());
        writer.setUser(user[0]);
        writer.setPassword(user[1]);
        SourceSettings settings = SourceSettings.ofServer(SourceType.MYSQL, uri.getHost(), uri.getPort(),
                uri.getPath().substring(1), user[0], user[1], prefix, false);
        try (Connection connection = writer.getConnection()) {
            // The name clashes with java.sql.Driver.
            serverScenario(connection, SourceType.MYSQL, prefix, settings, new org.mariadb.jdbc.Driver());
        }
    }

    @Test
    public void readsDuckDb() throws SQLException {
        assumeTrue("1".equals(System.getenv("HAWKEYE_TEST_COREPROTECT_DUCKDB")),
                "HAWKEYE_TEST_COREPROTECT_DUCKDB is not set");
        try (DriverResolver drivers = drivers()) {
            Driver driver = drivers.driver(DriverArtifact.DUCKDB);
            Path file = folder.resolve("database.duckdb");
            ReaderScenario.Expected expected;
            try (CoreProtectFixture db = CoreProtectFixture.duckdb(file, driver, Layout.CURRENT)) {
                expected = ReaderScenario.populate(db);
            }
            try (CoreProtectSource source = CoreProtectSource.open(SourceSettings.ofFile(SourceType.DUCKDB, file),
                    driver)) {
                assertEquals("rowid", source.order());
                ReaderScenario.verify(source, expected);
                assertAnchor(source, false);
            }
            Path empty = folder.resolve("empty.duckdb");
            Objects.requireNonNull(driver.connect("jdbc:duckdb:" + empty, new Properties())).close();
            assertNotCoreProtect(SourceSettings.ofFile(SourceType.DUCKDB, empty), driver);
            Path legacy = folder.resolve("legacy.duckdb");
            try (CoreProtectFixture db = CoreProtectFixture.duckdb(legacy, driver, Layout.LEGACY)) {
                legacyRows(db);
            }
            try (CoreProtectSource source = CoreProtectSource.open(SourceSettings.ofFile(SourceType.DUCKDB, legacy),
                    driver)) {
                assertLegacy(source);
            }
        }
    }

    @Test
    public void readsClickHouse() throws SQLException {
        URI uri = uri("HAWKEYE_TEST_COREPROTECT_CLICKHOUSE");
        String[] user = credentials(uri);
        String prefix = prefix();
        SourceSettings settings = SourceSettings.ofServer(SourceType.CLICKHOUSE, uri.getHost(), uri.getPort(),
                uri.getPath().substring(1), user[0], user[1], prefix, uri.getScheme().equals("https"));
        try (DriverResolver drivers = drivers()) {
            Driver driver = drivers.driver(DriverArtifact.CLICKHOUSE);
            Properties properties = SourceDialect.CLICKHOUSE.properties(settings);
            try (Connection connection = Objects.requireNonNull(driver.connect(SourceDialect.CLICKHOUSE.url(settings),
                    properties))) {
                serverScenario(connection, SourceType.CLICKHOUSE, prefix, settings, driver);
            }
        }
    }

    private static void serverScenario(Connection connection, SourceType engine, String prefix,
            SourceSettings settings, Driver driver) throws SQLException {
        CoreProtectFixture db = CoreProtectFixture.server(engine, connection, prefix, Layout.CURRENT);
        CoreProtectFixture legacy = CoreProtectFixture.server(engine, connection, prefix + "old_", Layout.LEGACY);
        try {
            ReaderScenario.Expected expected = ReaderScenario.populate(db);
            try (CoreProtectSource source = CoreProtectSource.open(settings, driver)) {
                assertEquals(engine == SourceType.CLICKHOUSE ? "time" : "rowid", source.order());
                ReaderScenario.verify(source, expected);
                assertAnchor(source, engine == SourceType.CLICKHOUSE);
                if (engine == SourceType.CLICKHOUSE) {
                    assertTimeOrder(db, source);
                }
            }
            assertNotCoreProtect(withPrefix(settings, prefix + "none_"), driver);
            if (engine == SourceType.CLICKHOUSE) {
                assertViewsAreTables(db, settings, driver, prefix);
            }
            legacyRows(legacy);
            SourceSettings old = SourceSettings.ofServer(engine, settings.host(), settings.port(), settings.database(),
                    settings.username(), settings.password(), prefix + "old_", settings.tls());
            try (CoreProtectSource source = CoreProtectSource.open(old, driver)) {
                assertLegacy(source);
            }
            if (engine == SourceType.MYSQL) {
                assertReadOnly(settings, driver, prefix);
            }
        } finally {
            db.drop();
            legacy.drop();
        }
    }

    /**
     * The scenario's two session rows: a cursor whose row is there with its time is kept; one whose row
     * has another time is re-anchored (rowid order); one whose row was deleted is kept, since a purge on
     * these engines keeps row ids. In time order every cursor is kept.
     */
    private static void assertAnchor(CoreProtectSource source, boolean timeOrder) throws SQLException {
        List<SourceRow> sessions = source.read(SourceTable.SESSION, SourceCursor.START, 10);
        assertEquals(2, sessions.size());
        SourceCursor first = sessions.getFirst().cursor();
        SourceCursor last = sessions.getLast().cursor();
        assertEquals(last, source.anchor(SourceTable.SESSION, last));
        SourceCursor shifted = new SourceCursor(first.time(), last.rowId());
        assertEquals(timeOrder ? shifted : first, source.anchor(SourceTable.SESSION, shifted),
                "the row at the cursor's rowid has another time");
        SourceCursor deleted = new SourceCursor(last.time(), last.rowId() + 1_000_000);
        assertEquals(deleted, source.anchor(SourceTable.SESSION, deleted));
        assertEquals(SourceCursor.START, source.anchor(SourceTable.SESSION, SourceCursor.START));
    }

    /** A database without CoreProtect's tables is refused when it is opened. */
    private static void assertNotCoreProtect(SourceSettings settings, Driver driver) {
        SQLException failure = assertThrows(SQLException.class, () -> CoreProtectSource.open(settings, driver).close());
        assertTrue(failure.getMessage().startsWith("not a CoreProtect database: no table " + settings.prefix()
                + "block"), failure.getMessage());
    }

    /** CoreProtect's ClickHouse tables are views: the catalog finds those as well. */
    private static void assertViewsAreTables(CoreProtectFixture db, SourceSettings settings, Driver driver,
            String prefix) throws SQLException {
        String views = prefix + "v_";
        try {
            for (CoreProtectTables.Table table : CoreProtectTables.tables(Layout.CURRENT)) {
                db.execute("CREATE VIEW `" + views + table.name() + "` AS SELECT * FROM `{prefix}" + table.name()
                        + "`");
            }
            try (CoreProtectSource source = CoreProtectSource.open(withPrefix(settings, views), driver)) {
                assertEquals(EnumSet.allOf(SourceTable.class), source.tables());
                assertEquals(ReaderScenario.INSTALLED + ":25.0", source.fingerprint());
            }
        } finally {
            for (CoreProtectTables.Table table : CoreProtectTables.tables(Layout.CURRENT)) {
                db.execute("DROP VIEW IF EXISTS `" + views + table.name() + "`");
            }
        }
    }

    private static SourceSettings withPrefix(SourceSettings settings, String prefix) {
        return SourceSettings.ofServer(settings.type(), settings.host(), settings.port(), settings.database(),
                settings.username(), settings.password(), prefix, settings.tls());
    }

    /** ClickHouse reads by (time, rowid): a row with a lower rowid but a later time comes after. */
    private static void assertTimeOrder(CoreProtectFixture db, CoreProtectSource source) throws SQLException {
        db.execute("INSERT INTO `{prefix}session` (rowid, `time`, `user`, wid, x, y, z, action) VALUES "
                + "(900, " + (ReaderScenario.INSTALLED + 2) + ", 1, 1, 0, 0, 0, 1), "
                + "(901, " + (ReaderScenario.INSTALLED + 1) + ", 1, 1, 0, 0, 0, 1), "
                + "(5, " + (ReaderScenario.INSTALLED + 1) + ", 1, 1, 0, 0, 0, 0)");
        List<SourceRow> rows = source.read(SourceTable.SESSION, SourceCursor.START, 3);
        assertEquals(List.of(5L, 901L, 900L), rows.stream().map(SourceRow::rowId).toList());
        assertEquals(List.of(900L), source.read(SourceTable.SESSION, rows.get(1).cursor(), 1).stream()
                .map(SourceRow::rowId).toList());
    }

    private static void legacyRows(CoreProtectFixture db) throws SQLException {
        int world = db.world("world");
        db.block(ReaderScenario.INSTALLED, 1, at(world, 1, 2, 3), 1, 0, "1", 1, 1);
        db.sign(ReaderScenario.INSTALLED + 1, 1, at(world, 1, 2, 3), 1, 1, List.of("a", "b", "c", "d", "e"), 1);
    }

    private static void assertLegacy(CoreProtectSource source) throws SQLException {
        assertEquals(EnumSet.of(SourceTable.BLOCK, SourceTable.SIGN, SourceTable.CONTAINER, SourceTable.ITEM,
                SourceTable.CHAT, SourceTable.COMMAND, SourceTable.SESSION), source.tables());
        SourceRow.Block block = (SourceRow.Block) source.read(SourceTable.BLOCK, SourceCursor.START, 5).getFirst();
        assertEquals(0, block.blockData().length);
        SourceRow.Sign sign = (SourceRow.Sign) source.read(SourceTable.SIGN, SourceCursor.START, 5).getFirst();
        assertEquals(List.of("a", "b", "c", "d", "", "", "", ""), sign.lines());
        assertEquals(0, sign.face());
        assertEquals(0, sign.rolledBack());
        assertTrue(source.fingerprint().startsWith("location-"));
        assertEquals(false, source.migrating());
    }

    /** A connection prepared the way a source prepares its own refuses to write. */
    private static void assertReadOnly(SourceSettings settings, Driver driver, String prefix) throws SQLException {
        SourceDialect dialect = SourceDialect.of(settings.type());
        try (Connection connection = Objects.requireNonNull(driver.connect(dialect.url(settings),
                dialect.properties(settings)))) {
            dialect.prepare(connection);
            try (Statement statement = connection.createStatement()) {
                assertThrows(SQLException.class, () -> statement.executeUpdate("INSERT INTO " + prefix
                        + "world (rowid, id, world) VALUES (999, 999, 'nope')"), "the session is read-only");
            }
        }
    }

    private static DriverResolver drivers() {
        Path cache = Path.of(System.getProperty("user.home"), ".cache", "hawkeye-ref", "drivers");
        return new DriverResolver(cache, SourceDrivers.MAVEN_CENTRAL,
                CoreProtectSourceIntegrationTest.class.getClassLoader());
    }

    private static URI uri(String variable) {
        String url = System.getenv(variable);
        assumeTrue(url != null && !url.isBlank(), variable + " is not set");
        return URI.create(url);
    }

    private static String[] credentials(URI uri) {
        String[] user = uri.getUserInfo() == null ? new String[] {"", ""} : uri.getUserInfo().split(":", 2);
        return user.length > 1 ? user : new String[] {user[0], ""};
    }

    private static String prefix() {
        return "cp" + Long.toHexString(System.nanoTime()).substring(0, 6).toLowerCase(Locale.ROOT) + "_";
    }
}
