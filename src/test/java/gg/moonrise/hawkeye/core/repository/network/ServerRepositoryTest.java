package gg.moonrise.hawkeye.core.repository.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.core.sql.Database;
import gg.moonrise.hawkeye.core.sql.SqliteHarness;
import gg.moonrise.hawkeye.entity.server.ServerNode;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ServerRepositoryTest {

    @TempDir
    private Path folder;

    private SqliteHarness harness;
    private Database database;
    private final ServerRepository servers = new ServerRepository();

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
    public void anImportNodeIsRegisteredOnceWithoutAHeartbeat() throws SQLException {
        ServerNode live = harness.register("survival", "survival");
        Optional<ServerNode> node = registerImport("survival-coreprotect", "survival");
        assertTrue(node.isPresent());
        assertEquals(new ServerNode(live.node() + 1, "survival-coreprotect", "survival", 0), node.get());
        assertTrue(node.get().imported());
        assertFalse(live.imported());

        assertEquals(Optional.empty(), registerImport("survival-coreprotect", "survival"), "the name is taken");
        assertEquals(Optional.empty(), registerImport("survival", "survival"), "a running server's name too");
        assertEquals(live, database.read(connection -> servers.byName(connection, database.sql(), "survival"))
                .orElseThrow(), "the running server is untouched");
        assertEquals(live.node() + 2, registerImport("survival-coreprotect-2", "survival").orElseThrow().node());
    }

    private Optional<ServerNode> registerImport(String name, String scope) throws SQLException {
        return database.read(connection -> servers.registerImport(connection, database.sql(), name, scope));
    }
}
