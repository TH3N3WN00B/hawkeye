package gg.moonrise.hawkeye.core.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import gg.moonrise.hawkeye.config.file.StorageSettings;
import java.nio.file.Path;
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

    private MigrationResult migrate() throws SQLException {
        return database.read(connection -> Migrations.apply(connection, database.dialect(), database.tables()));
    }
}
