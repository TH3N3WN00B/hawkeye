package gg.moonrise.hawkeye.core.repository.coreprotect;

import static gg.moonrise.hawkeye.core.repository.coreprotect.CoreProtectFixture.at;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.core.repository.coreprotect.CoreProtectFixture.Layout;
import gg.moonrise.hawkeye.entity.importer.SourceCursor;
import gg.moonrise.hawkeye.entity.importer.SourceRow;
import gg.moonrise.hawkeye.entity.importer.SourceSettings;
import gg.moonrise.hawkeye.entity.importer.SourceTable;
import gg.moonrise.hawkeye.entity.importer.SourceType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.JDBC;

/** Reading CoreProtect's SQLite layout: always runs (the other engines run in the integration test). */
public class CoreProtectSourceTest {

    @TempDir
    private Path folder;

    @Test
    public void readsEveryRowTypeAndColumn() throws SQLException {
        Path file = folder.resolve("database.db");
        ReaderScenario.Expected expected;
        try (CoreProtectFixture db = CoreProtectFixture.sqlite(file, Layout.CURRENT)) {
            expected = ReaderScenario.populate(db);
        }
        try (CoreProtectSource source = open(file)) {
            assertEquals("rowid", source.order());
            ReaderScenario.verify(source, expected);
        }
    }

    @Test
    public void anOlderSchemaReadsMissingColumnsAsEmptyAndLacksOptionalTables() throws SQLException {
        Path file = folder.resolve("database.db");
        try (CoreProtectFixture db = CoreProtectFixture.sqlite(file, Layout.LEGACY)) {
            int alex = db.user("Alex", null);
            int world = db.world("world");
            int stone = db.material("minecraft:stone");
            db.block(1_767_225_600, alex, at(world, 1, 2, 3), stone, 0, "1,2", 1, 1);
            db.sign(1_767_225_601, alex, at(world, 1, 2, 3), 1, 1, List.of("a", "b", "c", "d", "e", "f", "g", "h"), 1);
        }
        try (CoreProtectSource source = open(file)) {
            assertEquals(EnumSet.of(SourceTable.BLOCK, SourceTable.SIGN, SourceTable.CONTAINER, SourceTable.ITEM,
                    SourceTable.CHAT, SourceTable.COMMAND, SourceTable.SESSION), source.tables());
            SourceRow.Block block = (SourceRow.Block) source.read(SourceTable.BLOCK, SourceCursor.START, 10).getFirst();
            assertEquals(0, block.blockData().length, "no blockdata column");
            assertEquals(1, block.rolledBack());
            SourceRow.Sign sign = (SourceRow.Sign) source.read(SourceTable.SIGN, SourceCursor.START, 10).getFirst();
            assertEquals(List.of("a", "b", "c", "d", "", "", "", ""), sign.lines());
            assertEquals(0, sign.face());
            assertEquals(0, sign.rolledBack());
            assertEquals(Map.of(), source.names().blockData());
            assertFalse(source.migrating(), "no database_lock table");
            assertTrue(source.fingerprint().startsWith("location-"), "no version row");
        }
    }

    @Test
    public void windowsJumpLargeRowidGapsAndResumeFromACursor() throws SQLException {
        Path file = folder.resolve("database.db");
        try (CoreProtectFixture db = CoreProtectFixture.sqlite(file, Layout.CURRENT)) {
            int world = db.world("world");
            for (int i = 0; i < 5; i++) {
                db.chat(1_767_225_600 + i, 1, at(world, 0, 0, 0), "a" + i);
            }
            db.skipRowIds("chat", 50_000_000);
            db.chat(1_767_225_700, 1, at(world, 0, 0, 0), "after the gap");
            db.skipRowIds("chat", 3);
            db.chat(1_767_225_701, 1, at(world, 0, 0, 0), "last");
        }
        try (CoreProtectSource source = open(file)) {
            assertEquals(List.of(1L, 2L, 3L), rowIds(source.read(SourceTable.CHAT, SourceCursor.START, 3)));
            assertEquals(List.of(4L, 5L), rowIds(source.read(SourceTable.CHAT, new SourceCursor(0, 3), 3)));
            assertEquals(List.of(50_000_006L), rowIds(source.read(SourceTable.CHAT, new SourceCursor(0, 5), 3)),
                    "an empty window is jumped; a window holds the rows of limit row ids");
            assertEquals(List.of(50_000_010L), rowIds(source.read(SourceTable.CHAT, new SourceCursor(0, 50_000_006),
                    3)));
            assertEquals(List.of(), source.read(SourceTable.CHAT, new SourceCursor(0, 50_000_010), 3));
            assertEquals(50_000_010L, source.remaining(SourceTable.CHAT, SourceCursor.START));
            assertEquals(5L, source.remaining(SourceTable.CHAT, new SourceCursor(0, 50_000_005)));
            assertEquals(0L, source.remaining(SourceTable.CHAT, new SourceCursor(0, 60_000_000)), "never negative");
            assertEquals(List.of(), source.read(SourceTable.SESSION, SourceCursor.START, 3), "an empty table");
            assertEquals(0L, source.remaining(SourceTable.SESSION, SourceCursor.START));
        }
    }

    @Test
    public void rowidOrderIsKeptForBackDatedRows() throws SQLException {
        Path file = folder.resolve("database.db");
        try (CoreProtectFixture db = CoreProtectFixture.sqlite(file, Layout.CURRENT)) {
            db.sign(1_767_225_600, 1, at(1, 0, 0, 0), 1, 0, List.of(), 0);
            db.sign(1_767_225_595, 1, at(1, 0, 0, 0), 0, 0, List.of(), 0);
        }
        try (CoreProtectSource source = open(file)) {
            assertEquals(List.of(1_767_225_600L, 1_767_225_595L), source.read(SourceTable.SIGN, SourceCursor.START, 10)
                    .stream().map(SourceRow::time).toList());
        }
    }

    @Test
    public void anIncompleteMigrationIsReported() throws SQLException {
        Path file = folder.resolve("database.db");
        try (CoreProtectFixture db = CoreProtectFixture.sqlite(file, Layout.CURRENT)) {
            db.lockStatus(1);
        }
        try (CoreProtectSource source = open(file)) {
            assertFalse(source.migrating(), "status 1 is a running server");
        }
        try (CoreProtectFixture db = CoreProtectFixture.sqlite(file, Layout.CURRENT)) {
            db.execute("UPDATE {prefix}database_lock SET status = 2");
        }
        try (CoreProtectSource source = open(file)) {
            assertTrue(source.migrating());
        }
    }

    @Test
    public void aSpawnWithoutAnEntitySpawnRowHasNoUuid() throws SQLException {
        Path file = folder.resolve("database.db");
        try (CoreProtectFixture db = CoreProtectFixture.sqlite(file, Layout.LEGACY)) {
            db.block(1_767_225_600, 1, at(1, 0, 0, 0), 1, 7, null, 13, 0);
        }
        try (CoreProtectSource source = open(file)) {
            SourceRow.Block spawn = (SourceRow.Block) source.read(SourceTable.BLOCK, SourceCursor.START, 1).getFirst();
            assertEquals(7, spawn.data());
            assertNull(spawn.entity());
        }
    }

    @Test
    public void theFingerprintWithoutAVersionRowIsStablePerLocation() throws SQLException {
        Path first = folder.resolve("a.db");
        Path second = folder.resolve("b.db");
        CoreProtectFixture.sqlite(first, Layout.CURRENT).close();
        CoreProtectFixture.sqlite(second, Layout.CURRENT).close();
        try (CoreProtectSource a = open(first); CoreProtectSource again = open(first);
                CoreProtectSource b = open(second)) {
            assertEquals(a.fingerprint(), again.fingerprint());
            assertFalse(a.fingerprint().equals(b.fingerprint()));
        }
    }

    @Test
    public void aMissingFileIsNotCreated() {
        Path missing = folder.resolve("missing.db");
        assertThrows(SQLException.class, () -> open(missing).close());
        assertFalse(Files.exists(missing), "opened read-only");
    }

    @Test
    public void theSourceIsReadOnly() throws SQLException {
        Path file = folder.resolve("database.db");
        CoreProtectFixture.sqlite(file, Layout.CURRENT).close();
        try (CoreProtectSource source = open(file)) {
            assertEquals(Set.copyOf(List.of(SourceTable.values())), source.tables());
        }
        // A file engine always reads co_ tables, whatever prefix the settings carry.
        SourceSettings prefixed = new SourceSettings(SourceType.SQLITE, file, "", 0, "", "", "", "other_", false);
        try (CoreProtectSource source = CoreProtectSource.open(prefixed, new JDBC())) {
            assertEquals("co_", source.settings().prefix());
            assertEquals(9, source.tables().size());
        }
    }

    @Test
    public void anInvalidServerPrefixIsRefusedBeforeConnecting() {
        SourceSettings bad = SourceSettings.ofServer(SourceType.MYSQL, "127.0.0.1", 1, "db", "u", "secret",
                "co_; DROP", false);
        SQLException failure = assertThrows(SQLException.class, () -> CoreProtectSource.open(bad, new JDBC()));
        assertTrue(failure.getMessage().contains("prefix"), failure.getMessage());
        SourceSettings parameters = SourceSettings.ofServer(SourceType.MYSQL, "127.0.0.1", 1,
                "db?allowLoadLocalInfile=true", "u", "secret", "co_", false);
        failure = assertThrows(SQLException.class, () -> CoreProtectSource.open(parameters, new JDBC()));
        assertTrue(failure.getMessage().contains("host or database"), failure.getMessage());
    }

    @Test
    public void settingsNeverShowThePassword() {
        SourceSettings settings = SourceSettings.ofServer(SourceType.MYSQL, "127.0.0.1", 3306, "minecraft", "root",
                "hunter2", "co_", false);
        assertEquals("mysql root@127.0.0.1:3306/minecraft", settings.describe());
        assertEquals(settings.describe(), settings.toString());
        assertFalse(settings.toString().contains("hunter2"));
        assertEquals("clickhouse default@ch:8123/default prefix cp_ tls", SourceSettings.ofServer(SourceType.CLICKHOUSE,
                "ch", 8123, "default", "default", "", "cp_", true).describe());
        assertEquals("sqlite " + folder.resolve("database.db"), SourceSettings.ofFile(SourceType.SQLITE,
                folder.resolve("database.db")).describe());
    }

    private static CoreProtectSource open(Path file) throws SQLException {
        return CoreProtectSource.open(SourceSettings.ofFile(SourceType.SQLITE, file), new JDBC());
    }

    private static List<Long> rowIds(List<SourceRow> rows) {
        return rows.stream().map(SourceRow::rowId).toList();
    }
}
