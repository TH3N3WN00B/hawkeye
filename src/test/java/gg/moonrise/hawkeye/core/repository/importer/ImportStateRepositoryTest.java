package gg.moonrise.hawkeye.core.repository.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.core.sql.Database;
import gg.moonrise.hawkeye.core.sql.SqliteHarness;
import gg.moonrise.hawkeye.entity.importer.ImportState;
import gg.moonrise.hawkeye.entity.importer.source.SourceCursor;
import gg.moonrise.hawkeye.entity.importer.source.SourceTable;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ImportStateRepositoryTest {

    private static final String SOURCE = "coreprotect:survival:1735689600:25.0";

    @TempDir
    private Path folder;

    private SqliteHarness harness;
    private Database database;
    private final ImportStateRepository repository = new ImportStateRepository();

    @BeforeEach
    public void setUp() throws SQLException {
        harness = SqliteHarness.open(folder);
        database = harness.database();
    }

    @AfterEach
    public void tearDown() {
        harness.close();
    }

    @Test
    public void aStateIsStoredOnceAndReadBack() throws SQLException {
        assertEquals(Optional.empty(), find());
        ImportState fresh = ImportState.fresh("survival-coreprotect", "rowid", 1_000);
        assertTrue(insert(fresh));
        assertFalse(insert(ImportState.fresh("other-coreprotect", "rowid", 2_000)), "the first one stays");
        assertEquals(Optional.of(fresh), find());
    }

    @Test
    public void compareAndSetMovesOnlyFromTheHeldRevision() throws SQLException {
        ImportState fresh = ImportState.fresh("survival-coreprotect", "rowid", 1_000);
        insert(fresh);
        ImportState next = new ImportState(1, fresh.node(), fresh.order(),
                Map.of(SourceTable.CHAT, new SourceCursor(5, 6)), 99, 10, 0, Map.of(), false, 1_000, 2_000);
        assertTrue(compareAndSet(next));
        assertEquals(Optional.of(next.withRevision(2, 2_000)), find());
        assertFalse(compareAndSet(next), "revision 1 is gone: a second run holding it is stopped");
        assertTrue(compareAndSet(next.withRevision(2, 3_000)));
        assertEquals(3, find().orElseThrow().revision());
        assertEquals(3, stored("revision"));
        assertEquals(3_000, stored("updated"));
    }

    @Test
    public void anOverlongSourceKeyIsRefused() {
        String longKey = "x".repeat(ImportStateRepository.MAX_SOURCE_LENGTH + 1);
        assertThrows(SQLException.class, () -> database.read(connection -> repository.insert(connection,
                database.sql(), longKey, ImportState.fresh("n", "rowid", 0))));
    }

    @Test
    public void unreadableStateTextIsAnError() throws SQLException {
        database.read(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO " + database.tables().importState() + " VALUES ('" + SOURCE
                        + "', 1, 'v=9', 0)");
            }
            return null;
        });
        assertThrows(SQLException.class, this::find);
    }

    private boolean insert(ImportState state) throws SQLException {
        return database.read(connection -> repository.insert(connection, database.sql(), SOURCE, state));
    }

    private boolean compareAndSet(ImportState next) throws SQLException {
        return database.read(connection -> repository.compareAndSet(connection, database.sql(), SOURCE, next));
    }

    private Optional<ImportState> find() throws SQLException {
        return database.read(connection -> repository.find(connection, database.sql(), SOURCE));
    }

    private long stored(String column) throws SQLException {
        return database.read(connection -> {
            try (Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery("SELECT " + column + " FROM "
                            + database.tables().importState())) {
                rows.next();
                return rows.getLong(1);
            }
        });
    }
}
