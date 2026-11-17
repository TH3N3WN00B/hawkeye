package gg.moonrise.hawkeye.core.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import gg.moonrise.hawkeye.config.file.StorageSettings;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
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

    @Test
    public void version4MarksTheExistingSegmentsAsNotIndexed() throws SQLException {
        Tables t = database.tables();
        database.read(connection -> {
            try (Statement statement = connection.createStatement()) {
                applyUpTo(statement, 3);
                for (int i = 0; i < 3; i++) {
                    statement.execute("INSERT INTO " + t.segment() + " (first_id, last_id, events, header, body) "
                            + "VALUES (1, 2, 2, x'02', x'00')");
                }
                statement.execute("DELETE FROM " + t.segment() + " WHERE id = 3");
                for (String sql : Schema.statements(4, database.dialect(), t)) {
                    statement.execute(sql);
                }
                assertEquals(3, count(statement, "SELECT indexed_from FROM " + t.legacyPlaceState()),
                        "segments 1 and 2 exist and are not indexed");
                assertEquals(0, count(statement, "SELECT COUNT(*) FROM " + t.legacyPlaceRegion()));
                assertEquals(0, count(statement, "SELECT COUNT(*) FROM " + t.legacyPlaceChunk()));
                for (String sql : Schema.statements(4, database.dialect(), t)) {
                    statement.execute(sql);
                }
                assertEquals(1, count(statement, "SELECT COUNT(*) FROM " + t.legacyPlaceState()), "idempotent");
                assertEquals(3, count(statement, "SELECT indexed_from FROM " + t.legacyPlaceState()));
            }
            return null;
        });
    }

    @Test
    public void version5ReplacesTheVersion4IndexAndMarksEverySegmentAsNotIndexed() throws SQLException {
        Tables t = database.tables();
        database.read(connection -> {
            try (Statement statement = connection.createStatement()) {
                applyUpTo(statement, 4);
                statement.execute("INSERT INTO " + t.schemaVersion() + " VALUES (1, 4)");
                for (int i = 0; i < 4; i++) {
                    statement.execute("INSERT INTO " + t.segment() + " (first_id, last_id, events, header, body) "
                            + "VALUES (1, 2, 2, x'02', x'00')");
                }
                statement.execute("DELETE FROM " + t.segment() + " WHERE id = 2");
                statement.execute("UPDATE " + t.legacyPlaceState() + " SET indexed_from = 1");
                statement.execute("INSERT INTO " + t.legacyPlaceRegion() + " VALUES (1, 0, 4, x'00')");
                statement.execute("INSERT INTO " + t.legacyPlaceChunk() + " VALUES (1, 0, 4, x'00')");
            }
            return null;
        });
        assertEquals(new MigrationResult.Migrated(4, 5), migrate());
        database.read(connection -> {
            try (Statement statement = connection.createStatement()) {
                for (String table : List.of(t.legacyPlaceRegion(), t.legacyPlaceChunk(), t.legacyPlaceState())) {
                    assertEquals(0, count(statement, "SELECT COUNT(*) FROM sqlite_master WHERE name = '" + table
                            + "'"), table + " is gone");
                }
                for (String table : List.of(t.regionSegment(), t.blockSegment(), t.actorSegment())) {
                    assertEquals(0, count(statement, "SELECT COUNT(*) FROM " + table), table + " is empty");
                }
                assertEquals(5, count(statement, "SELECT indexed_from FROM " + t.segmentIndex()),
                        "segments 1, 3 and 4 exist and are not indexed");
                statement.execute("UPDATE " + t.segmentIndex() + " SET indexed_from = 2");
                for (String sql : Schema.statements(5, database.dialect(), t)) {
                    statement.execute(sql);
                }
                assertEquals(1, count(statement, "SELECT COUNT(*) FROM " + t.segmentIndex()), "idempotent");
                assertEquals(2, count(statement, "SELECT indexed_from FROM " + t.segmentIndex()),
                        "running version 5 again keeps the state");
            }
            return null;
        });
    }

    @Test
    public void aFreshDatabaseEndsAtVersion5IndexingFromTheFirstSegment() throws SQLException {
        assertEquals(new MigrationResult.Migrated(0, 5), migrate());
        database.read(connection -> {
            try (Statement statement = connection.createStatement()) {
                assertEquals(1, count(statement, "SELECT indexed_from FROM " + database.tables().segmentIndex()));
                assertEquals(5, Migrations.version(connection, database.tables()));
            }
            return null;
        });
    }

    private void applyUpTo(Statement statement, int version) throws SQLException {
        statement.execute("CREATE TABLE IF NOT EXISTS " + database.tables().schemaVersion()
                + " (id INTEGER NOT NULL PRIMARY KEY, version INTEGER NOT NULL)");
        for (int next = 1; next <= version; next++) {
            for (String sql : Schema.statements(next, database.dialect(), database.tables())) {
                statement.execute(sql);
            }
        }
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
