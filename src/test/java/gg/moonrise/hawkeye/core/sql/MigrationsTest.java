package gg.moonrise.hawkeye.core.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import gg.moonrise.hawkeye.config.file.StorageSettings;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class MigrationsTest {

    @TempDir
    private Path folder;

    private Database database;

    @BeforeEach
    public void setUp() {
        database = new DatabaseFactory().open("home", new StorageSettings().withSqliteFile("m.db"), folder);
    }

    @AfterEach
    public void tearDown() {
        database.close();
    }

    @Test
    public void createsTheSchemaOnceAndIsIdempotent() throws SQLException {
        assertEquals(new MigrationResult.Migrated(0, Schema.CURRENT), migrate());
        assertEquals(new MigrationResult.UpToDate(Schema.CURRENT), migrate());
        // Every statement of the current version can run again (a second server migrating at once).
        database.read(connection -> {
            try (Statement statement = connection.createStatement()) {
                for (String sql : Schema.statements(1, database.dialect(), database.tables())) {
                    statement.execute(sql);
                }
            }
            return null;
        });
    }

    @Test
    public void refusesADatabaseFromANewerVersion() throws SQLException {
        migrate();
        database.read(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("UPDATE " + database.tables().schemaVersion() + " SET version = 99");
            }
            return null;
        });
        assertInstanceOf(MigrationResult.DatabaseAhead.class, migrate());
    }

    @Test
    public void version3DiscardsTheDevelopmentArchiveAndKeepsHotEvents() throws SQLException {
        Tables t = database.tables();
        database.read(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS " + t.schemaVersion()
                        + " (id INTEGER NOT NULL PRIMARY KEY, version INTEGER NOT NULL)");
                for (int version = 1; version <= 2; version++) {
                    for (String sql : Schema.statements(version, database.dialect(), t)) {
                        statement.execute(sql);
                    }
                }
                statement.execute("INSERT INTO " + t.schemaVersion() + " VALUES (1, 2)");
                statement.execute("INSERT INTO " + t.event() + " (id, world, pos, actor, action, subject) "
                        + "VALUES (7, 1, 0, 1, 1, 1)");
                statement.execute("INSERT INTO " + t.segment() + " (world, region, first_id, last_id, events, body) "
                        + "VALUES (1, 0, 1, 2, 2, x'00')");
                statement.execute("INSERT INTO " + t.legacySegmentActor() + " VALUES (1, 1)");
            }
            return null;
        });
        assertEquals(new MigrationResult.Migrated(2, Schema.CURRENT), migrate());
        database.read(connection -> {
            try (Statement statement = connection.createStatement()) {
                assertEquals(1, count(statement, "SELECT COUNT(*) FROM " + t.event()));
                assertEquals(0, count(statement, "SELECT COUNT(*) FROM " + t.segment()));
                assertEquals(0, count(statement, "SELECT COUNT(*) FROM " + t.segmentFlag()));
                assertEquals(0, count(statement, "SELECT COUNT(*) FROM sqlite_master WHERE name = '"
                        + t.legacySegmentActor() + "'"));
                statement.execute("INSERT INTO " + t.segment() + " (first_id, last_id, events, header, body) "
                        + "VALUES (1, 2, 2, x'02', x'00')");
            }
            return null;
        });
    }

    private static long count(Statement statement, String sql) throws SQLException {
        try (ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private MigrationResult migrate() throws SQLException {
        return database.read(connection -> Migrations.apply(connection, database.dialect(), database.tables()));
    }
}
