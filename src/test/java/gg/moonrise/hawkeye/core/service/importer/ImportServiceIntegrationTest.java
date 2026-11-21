package gg.moonrise.hawkeye.core.service.importer;

import static gg.moonrise.hawkeye.core.repository.coreprotect.CoreProtectFixture.at;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import gg.moonrise.hawkeye.core.repository.coreprotect.CoreProtectFixture;
import gg.moonrise.hawkeye.core.repository.coreprotect.CoreProtectFixture.Layout;
import gg.moonrise.hawkeye.core.repository.coreprotect.DriverArtifact;
import gg.moonrise.hawkeye.core.repository.coreprotect.SourceDialect;
import gg.moonrise.hawkeye.core.repository.coreprotect.SourceDrivers;
import gg.moonrise.hawkeye.entity.importer.SkipReason;
import gg.moonrise.hawkeye.entity.importer.SourceSettings;
import gg.moonrise.hawkeye.entity.importer.SourceType;
import gg.moonrise.hawkeye.support.TestPlugins;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.Driver;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mariadb.jdbc.MariaDbDataSource;
import org.mockbukkit.mockbukkit.MockBukkit;

/**
 * The whole import ({@link ImportScenario}) from real CoreProtect engines into the plugin's SQLite
 * storage, on MockBukkit. Opt-in, with the variables of {@code CoreProtectSourceIntegrationTest}:
 * {@code HAWKEYE_TEST_COREPROTECT_MYSQL=mariadb://user:password@host:port/database},
 * {@code HAWKEYE_TEST_COREPROTECT_DUCKDB=1} (set {@code JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=...} to keep the
 * native library and the files off a RAM-backed {@code /tmp}) and
 * {@code HAWKEYE_TEST_COREPROTECT_CLICKHOUSE=http://user:password@host:port/database}. The server engines are
 * found through a CoreProtect {@code config.yml}, as on a real server. The on-demand drivers come from the
 * plugin's own {@code drivers} folder, linked from {@code ~/.cache/hawkeye-ref/drivers} when cached there.
 */
public class ImportServiceIntegrationTest {

    @TempDir
    private Path folder;

    private ImportService service;

    @BeforeEach
    public void setUp() {
        MockBukkit.mock();
        TestPlugins.load();
        TestPlugins.awaitStorage();
        service = TestPlugins.bean(ImportService.class);
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void importsFromMysql() throws Exception {
        URI uri = uri("HAWKEYE_TEST_COREPROTECT_MYSQL");
        String[] user = credentials(uri);
        String prefix = prefix();
        MariaDbDataSource writer = new MariaDbDataSource("jdbc:mariadb://" + uri.getHost() + ":" + uri.getPort()
                + uri.getPath());
        writer.setUser(user[0]);
        writer.setPassword(user[1]);
        try (Connection connection = writer.getConnection()) {
            CoreProtectFixture db = CoreProtectFixture.server(SourceType.MYSQL, connection, prefix, Layout.CURRENT);
            try {
                ImportScenario.populate(db, "25.0");
                Path config = config("database-type: mysql", "table-prefix: " + prefix,
                        "mysql-host: " + uri.getHost(), "mysql-port: " + uri.getPort(),
                        "mysql-database: " + uri.getPath().substring(1), "mysql-username: " + user[0],
                        "mysql-password: '" + user[1] + "'");
                importAndVerify(config, "mysql " + user[0] + "@" + uri.getHost());
                horizon(config, db, false);
            } finally {
                db.drop();
            }
        }
    }

    @Test
    public void importsFromDuckDb() throws Exception {
        assumeTrue("1".equals(System.getenv("HAWKEYE_TEST_COREPROTECT_DUCKDB")),
                "HAWKEYE_TEST_COREPROTECT_DUCKDB is not set");
        Driver driver = driver(DriverArtifact.DUCKDB, SourceType.DUCKDB);
        Path file = folder.resolve("database.duckdb");
        try (CoreProtectFixture db = CoreProtectFixture.duckdb(file, driver, Layout.CURRENT)) {
            ImportScenario.populate(db, "25.0");
        }
        importAndVerify(file, "duckdb " + file);
    }

    @Test
    public void importsFromClickHouse() throws Exception {
        URI uri = uri("HAWKEYE_TEST_COREPROTECT_CLICKHOUSE");
        String[] user = credentials(uri);
        String prefix = prefix();
        SourceSettings settings = SourceSettings.ofServer(SourceType.CLICKHOUSE, uri.getHost(), uri.getPort(),
                uri.getPath().substring(1), user[0], user[1], prefix, uri.getScheme().equals("https"));
        Driver driver = driver(DriverArtifact.CLICKHOUSE, SourceType.CLICKHOUSE);
        try (Connection connection = Objects.requireNonNull(driver.connect(SourceDialect.CLICKHOUSE.url(settings),
                SourceDialect.CLICKHOUSE.properties(settings)))) {
            CoreProtectFixture db = CoreProtectFixture.server(SourceType.CLICKHOUSE, connection, prefix,
                    Layout.CURRENT);
            try {
                ImportScenario.populate(db, "25.0");
                Path config = config("database-type: clickhouse", "table-prefix: " + prefix,
                        "clickhouse-host: " + uri.getHost(), "clickhouse-port: " + uri.getPort(),
                        "clickhouse-database: " + uri.getPath().substring(1), "clickhouse-username: " + user[0],
                        "clickhouse-password: '" + user[1] + "'", "clickhouse-tls: " + settings.tls());
                importAndVerify(config, "clickhouse " + user[0] + "@" + uri.getHost());
                horizon(config, db, true);
            } finally {
                db.drop();
            }
        }
    }

    /** Imports, checks every event, and checks that a second run adds nothing. */
    private void importAndVerify(@NotNull Path source, @NotNull String described) throws Exception {
        ImportOutcome.Finished finished = assertInstanceOf(ImportOutcome.Finished.class,
                service.start(source.toString(), ImportListener.NONE).get(120, TimeUnit.SECONDS));
        assertEquals(ImportScenario.EVENTS, finished.totals().imported());
        assertEquals(ImportScenario.SKIPPED, ImportScenario.skipped(finished.totals()));
        assertTrue(finished.totals().source().startsWith(described), finished.totals().source());
        ImportScenario.verify(ImportScenario.of("server-coreprotect"));

        ImportOutcome.Finished again = assertInstanceOf(ImportOutcome.Finished.class,
                service.start(source.toString(), ImportListener.NONE).get(120, TimeUnit.SECONDS));
        assertEquals(0, again.totals().imported());
        assertEquals(ImportScenario.EVENTS, ImportScenario.all().size());
    }

    /**
     * Rows CoreProtect logs after the scenario was imported: one settled, one within the server engine's
     * settle time and one from a clock far ahead. In rowid order the far one is consumed first and skipped;
     * in time order it is never consumed, so a row logged later with an earlier time is still imported.
     */
    private void horizon(@NotNull Path source, @NotNull CoreProtectFixture db, boolean timeOrder) throws Exception {
        long now = System.currentTimeMillis() / 1000;
        db.chat(now + 30L * 24 * 3_600, 1, at(1, 0, 70, 0), "from a clock far ahead");
        db.chat(now - 600, 1, at(1, 0, 70, 0), "settled");
        db.chat(now + 60, 1, at(1, 0, 70, 0), "within the settle time");

        ImportOutcome.Finished first = assertInstanceOf(ImportOutcome.Finished.class,
                service.start(source.toString(), ImportListener.NONE).get(120, TimeUnit.SECONDS));
        assertEquals(1, first.totals().imported(), "only the settled row");
        assertEquals(timeOrder ? 0 : 1, first.totals().skipped(SkipReason.BEFORE_EPOCH),
                "the far row is skipped in rowid order and never read in time order");
        assertEquals(timeOrder ? 2 : 1, first.totals().rowsRemaining());

        db.chat(now - 300, 1, at(1, 0, 70, 0), "logged late with an earlier time");
        ImportOutcome.Finished second = assertInstanceOf(ImportOutcome.Finished.class,
                service.start(source.toString(), ImportListener.NONE).get(120, TimeUnit.SECONDS));
        assertEquals(timeOrder ? 1 : 0, second.totals().imported(),
                "time order reads it; rowid order stops at the unsettled row before it");
        assertEquals(0, second.totals().skippedTotal());
        assertEquals(ImportScenario.EVENTS + 1 + (timeOrder ? 1 : 0), ImportScenario.all().size());
    }

    /** The plugin's driver for an engine, its jar linked or copied from the cache when the cache has it. */
    private @NotNull Driver driver(@NotNull DriverArtifact artifact, @NotNull SourceType type) throws Exception {
        SourceDrivers drivers = TestPlugins.bean(SourceDrivers.class);
        Path cached = Path.of(System.getProperty("user.home"), ".cache", "hawkeye-ref", "drivers", artifact.fileName());
        Path jar = drivers.folder().resolve(artifact.fileName());
        if (Files.isRegularFile(cached) && !Files.exists(jar)) {
            Files.createDirectories(jar.getParent());
            link(cached, jar);
        }
        return drivers.driver(type);
    }

    private static void link(@NotNull Path cached, @NotNull Path jar) throws IOException {
        try {
            Files.createLink(jar, cached);
        } catch (IOException | UnsupportedOperationException e) {
            Files.copy(cached, jar, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private @NotNull Path config(@NotNull String... lines) throws IOException {
        Path dir = Files.createDirectories(folder.resolve("CoreProtect"));
        Files.write(dir.resolve("config.yml"), List.of(lines));
        return dir;
    }

    private static @NotNull URI uri(@NotNull String variable) {
        String url = System.getenv(variable);
        assumeTrue(url != null && !url.isBlank(), variable + " is not set");
        return URI.create(url);
    }

    private static @NotNull String @NotNull [] credentials(@NotNull URI uri) {
        String[] user = uri.getUserInfo() == null ? new String[] {"", ""} : uri.getUserInfo().split(":", 2);
        return user.length > 1 ? user : new String[] {user[0], ""};
    }

    private static @NotNull String prefix() {
        return "cpi" + Long.toHexString(System.nanoTime()).substring(0, 6).toLowerCase(Locale.ROOT) + "_";
    }
}
