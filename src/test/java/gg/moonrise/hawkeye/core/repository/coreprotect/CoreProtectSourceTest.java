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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.JDBC;

/** Reading CoreProtect's SQLite layout: always runs (the other engines run in the integration test). */
public class CoreProtectSourceTest {

    private static final long T = ReaderScenario.INSTALLED;

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
    public void aCursorIsKeptWhileItsRowIdStillMeansTheSameRow() throws SQLException {
        Path file = folder.resolve("database.db");
        try (CoreProtectFixture db = CoreProtectFixture.sqlite(file, Layout.CURRENT)) {
            for (int i = 0; i < 5; i++) {
                db.chat(T + i, 1, at(1, 0, 0, 0), "a" + i);
                db.block(T + i, 1, at(1, i, 0, 0), 1, 0, null, 1, 0);
            }
            db.execute("DELETE FROM {prefix}block WHERE rowid <= 4");
        }
        try (CoreProtectSource source = open(file)) {
            assertEquals(SourceCursor.START, source.anchor(SourceTable.CHAT, SourceCursor.START));
            SourceCursor third = new SourceCursor(T + 2, 3);
            assertEquals(third, source.anchor(SourceTable.CHAT, third), "the row is there with the same time");
            SourceCursor purged = new SourceCursor(T + 3, 4);
            assertEquals(purged, source.anchor(SourceTable.BLOCK, purged),
                    "a purge keeps block's row ids: a deleted cursor row is simply passed");
        }
    }

    @Test
    public void aRenumberedTableIsReanchoredByTime() throws SQLException {
        Path file = folder.resolve("database.db");
        try (CoreProtectFixture db = CoreProtectFixture.sqlite(file, Layout.CURRENT)) {
            for (int i = 0; i < 10; i++) {
                db.chat(T + i, 1, at(1, 0, 0, 0), "a" + i);
                db.command(T + i, 1, at(1, 0, 0, 0), "/c" + i);
            }
            // What /co purge does on SQLite: the rows from T + 6 on, rebuilt and numbered from 1.
            renumber(db, "chat", T + 6);
            renumber(db, "command", T + 6);
            db.execute("INSERT INTO {prefix}command (time, user, wid, x, y, z, message) VALUES (" + (T + 20)
                    + ", 1, 1, 0, 0, 0, '/later')");
        }
        try (CoreProtectSource source = open(file)) {
            assertEquals(new SourceCursor(T + 9, 4), source.anchor(SourceTable.CHAT, new SourceCursor(T + 9, 10)),
                    "the cursor row is gone: after the last row stamped up to its time");
            assertEquals(new SourceCursor(T + 7, 2), source.anchor(SourceTable.CHAT, new SourceCursor(T + 7, 3)),
                    "rowid 3 holds another row now");
            assertEquals(new SourceCursor(T + 3, 0), source.anchor(SourceTable.CHAT, new SourceCursor(T + 3, 4)),
                    "nothing left at or before the cursor: from the start");
            assertEquals(new SourceCursor(T + 9, 4), source.anchor(SourceTable.COMMAND,
                    new SourceCursor(T + 9, 5)), "rowid 5 holds a newer row");
            assertEquals(List.of(T + 20), source.read(SourceTable.COMMAND, new SourceCursor(T + 9, 4), 10).stream()
                    .map(SourceRow::time).toList());
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
    public void aDatabaseWithoutCoreProtectsTablesIsRefused() throws SQLException, IOException {
        Path empty = Files.createFile(folder.resolve("empty.db"));
        SQLException failure = assertThrows(SQLException.class, () -> open(empty).close());
        assertTrue(failure.getMessage().startsWith("not a CoreProtect database: no table co_block"),
                failure.getMessage());

        Path other = folder.resolve("other.db");
        sql(other, "CREATE TABLE co_block (time INTEGER)", "CREATE TABLE co_user (id INTEGER PRIMARY KEY, user TEXT)");
        failure = assertThrows(SQLException.class, () -> open(other).close());
        assertTrue(failure.getMessage().startsWith("not a CoreProtect database: no table co_world"),
                failure.getMessage());

        Path garbage = Files.writeString(folder.resolve("garbage.db"), "not a database, only text. ".repeat(200));
        assertThrows(SQLException.class, () -> open(garbage).close());
    }

    @Test
    public void aTableTheCatalogListsButThatCannotBeReadIsAnErrorNotAbsent() throws SQLException {
        Path file = folder.resolve("database.db");
        CoreProtectFixture.sqlite(file, Layout.CURRENT).close();
        sql(file, "DROP TABLE co_chat", "CREATE VIEW co_chat AS SELECT * FROM co_gone", "DROP TABLE co_session");
        try (CoreProtectSource source = open(file)) {
            assertThrows(SQLException.class, source::tables, "a broken table must not be skipped silently");
        }
        sql(file, "DROP VIEW co_chat");
        try (CoreProtectSource source = open(file)) {
            Set<SourceTable> tables = source.tables();
            assertFalse(tables.contains(SourceTable.CHAT), "a missing table is simply absent");
            assertFalse(tables.contains(SourceTable.SESSION));
            assertTrue(tables.contains(SourceTable.BLOCK));
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

    /**
     * Rebuilds a table as CoreProtect's SQLite purge does: the rows stamped from {@code keepFrom} on, in
     * their order, numbered from 1.
     */
    private static void renumber(CoreProtectFixture db, String table, long keepFrom) throws SQLException {
        db.execute("CREATE TABLE {prefix}kept AS SELECT * FROM {prefix}" + table + " WHERE time >= " + keepFrom
                + " ORDER BY rowid");
        db.execute("DELETE FROM {prefix}" + table);
        db.execute("INSERT INTO {prefix}" + table + " SELECT * FROM {prefix}kept ORDER BY rowid");
        db.execute("DROP TABLE {prefix}kept");
    }

    private static void sql(Path file, String... statements) throws SQLException {
        try (Connection connection = new JDBC().connect("jdbc:sqlite:" + file, new Properties());
                Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    private static CoreProtectSource open(Path file) throws SQLException {
        return CoreProtectSource.open(SourceSettings.ofFile(SourceType.SQLITE, file), new JDBC());
    }

    private static List<Long> rowIds(List<SourceRow> rows) {
        return rows.stream().map(SourceRow::rowId).toList();
    }
}
