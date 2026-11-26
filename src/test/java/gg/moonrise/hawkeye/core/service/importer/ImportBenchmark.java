package gg.moonrise.hawkeye.core.service.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import gg.moonrise.hawkeye.core.sql.Database;
import gg.moonrise.hawkeye.core.storage.shard.StorageState;
import gg.moonrise.hawkeye.support.TestHawkeyePlugin;
import gg.moonrise.hawkeye.support.TestPlugins;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.sqlite.SQLiteConfig;

/**
 * Opt-in import benchmark: a real CoreProtect SQLite database imported through {@link ImportService} with the
 * default settings into the plugin's own SQLite storage on MockBukkit, then both files measured as they are
 * and compacted ({@code VACUUM INTO}). Run with {@code HAWKEYE_BENCH_COREPROTECT=<database.db>
 * HAWKEYE_BENCH_DIR=<folder on disk>}; the report is {@code import-report.json} in that folder. The source is
 * opened read-only, and its compacted copy is written to the folder and deleted again.
 */
@Slf4j
public class ImportBenchmark {

    private static final long TIMEOUT_HOURS = 6;

    @AfterEach
    public void tearDown() {
        if (MockBukkit.isMocked()) {
            MockBukkit.unmock();
        }
    }

    @Test
    public void aCoreProtectDatabase() throws Exception {
        String configured = System.getenv("HAWKEYE_BENCH_COREPROTECT");
        String directory = System.getenv("HAWKEYE_BENCH_DIR");
        assumeTrue(configured != null && !configured.isBlank() && directory != null && !directory.isBlank(),
                "HAWKEYE_BENCH_COREPROTECT and HAWKEYE_BENCH_DIR are not set");
        Path source = Path.of(configured).toAbsolutePath();
        Path folder = Files.createDirectories(Path.of(directory));

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("source", source(source, folder));

        MockBukkit.mock();
        TestHawkeyePlugin plugin = TestPlugins.load();
        StorageState.Ready ready = TestPlugins.awaitStorage();
        ImportService service = TestPlugins.bean(ImportService.class);

        long began = System.nanoTime();
        ImportOutcome outcome = service.start(source.toString(), new ImportListener() {
            @Override
            public void progress(@NotNull ImportProgress progress) {
                log.info("import: {} rows read, {} imported, {} left, {} s", progress.rowsRead(),
                        progress.imported(), progress.rowsRemaining(), progress.elapsedMillis() / 1000);
            }
        }).get(TIMEOUT_HOURS, TimeUnit.HOURS);
        double seconds = (System.nanoTime() - began) / 1e9;
        ImportOutcome.Finished finished = assertInstanceOf(ImportOutcome.Finished.class, outcome);
        report.put("import", run(finished.totals(), seconds));
        Map<String, Object> hawkeye = target(ready.home().database(), plugin.getDataPath().resolve("hawkeye.db"),
                folder);
        assertEquals(finished.totals().imported(), hawkeye.get("archivedEvents"), "every imported event is stored");
        report.put("hawkeye", hawkeye);

        String json = json(report);
        Files.writeString(folder.resolve("import-report.json"), json, StandardCharsets.UTF_8);
        log.info("import benchmark: {}", json);
    }

    /** The CoreProtect file: rows per table, bytes per table and index, and a compacted copy's size. */
    private static @NotNull Map<String, Object> source(@NotNull Path file, @NotNull Path folder)
            throws IOException, SQLException {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("bytes", Files.size(file));
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        try (Connection connection = config.createConnection("jdbc:sqlite:" + file)) {
            result.put("pageSize", pragma(connection, "page_size"));
            result.put("freePages", pragma(connection, "freelist_count"));
            Map<String, Object> rows = new TreeMap<>();
            for (String table : strings(connection, "SELECT name FROM sqlite_master WHERE type = 'table'")) {
                rows.put(table, count(connection, "SELECT COUNT(*) FROM \"" + table.replace("\"", "\"\"") + "\""));
            }
            result.put("rows", rows);
            result.put("pages", tableBytes(connection));
            Path compacted = folder.resolve("coreprotect-vacuumed.db");
            Files.deleteIfExists(compacted);
            vacuumInto(connection, compacted);
            result.put("vacuumedBytes", Files.size(compacted));
            Files.delete(compacted);
        }
        return result;
    }

    private static @NotNull Map<String, Object> run(@NotNull ImportProgress totals, double seconds) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("seconds", seconds);
        result.put("rowsRead", totals.rowsRead());
        result.put("imported", totals.imported());
        result.put("rowsPerSecond", totals.rowsRead() / seconds);
        result.put("displaced", totals.displaced());
        result.put("skipped", totals.skipped().entrySet().stream().filter(entry -> entry.getValue() > 0)
                .collect(Collectors.toMap(entry -> entry.getKey().name(), Map.Entry::getValue, Long::sum,
                        TreeMap::new)));
        return result;
    }

    /** Hawkeye's file after the import: tiers, bytes per table and index, and a compacted copy's size. */
    private static @NotNull Map<String, Object> target(@NotNull Database database, @NotNull Path file,
            @NotNull Path folder) throws IOException, SQLException {
        Map<String, Object> result = new LinkedHashMap<>();
        Path compacted = folder.resolve("hawkeye-vacuumed.db");
        Files.deleteIfExists(compacted);
        database.read(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            }
            result.put("pageSize", pragma(connection, "page_size"));
            result.put("freePages", pragma(connection, "freelist_count"));
            result.put("hotEvents", count(connection, "SELECT COUNT(*) FROM " + database.tables().event()));
            result.put("segments", count(connection, "SELECT COUNT(*) FROM " + database.tables().segment()));
            result.put("archivedEvents", count(connection, "SELECT COALESCE(SUM(events), 0) FROM "
                    + database.tables().segment()));
            result.put("headerBytes", count(connection, "SELECT COALESCE(SUM(LENGTH(header)), 0) FROM "
                    + database.tables().segment()));
            result.put("bodyBytes", count(connection, "SELECT COALESCE(SUM(LENGTH(body)), 0) FROM "
                    + database.tables().segment()));
            result.put("pages", tableBytes(connection));
            vacuumInto(connection, compacted);
            return null;
        });
        result.put("bytes", Files.size(file));
        result.put("vacuumedBytes", Files.size(compacted));
        Files.delete(compacted);
        return result;
    }

    private static void vacuumInto(@NotNull Connection connection, @NotNull Path target) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("VACUUM INTO '" + target.toAbsolutePath().toString().replace("'", "''") + "'");
        }
    }

    /** Bytes per table and index, from SQLite's {@code dbstat} table; empty when the build lacks it. */
    private static @NotNull Map<String, Object> tableBytes(@NotNull Connection connection) {
        Map<String, Object> result = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT name, SUM(pgsize) FROM dbstat GROUP BY name "
                        + "ORDER BY SUM(pgsize) DESC")) {
            while (rows.next()) {
                result.put(rows.getString(1), rows.getLong(2));
            }
        } catch (SQLException e) {
            log.warn("dbstat is not available", e);
        }
        return result;
    }

    private static @NotNull List<String> strings(@NotNull Connection connection, @NotNull String sql)
            throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            List<String> result = new ArrayList<>();
            while (rows.next()) {
                result.add(rows.getString(1));
            }
            return result;
        }
    }

    private static long count(@NotNull Connection connection, @NotNull String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static long pragma(@NotNull Connection connection, @NotNull String name) throws SQLException {
        return count(connection, "PRAGMA " + name);
    }

    /** A small JSON writer for the report's maps, numbers and strings. */
    private static @NotNull String json(Object value) {
        return switch (value) {
            case null -> "null";
            case Map<?, ?> map -> map.entrySet().stream()
                    .map(entry -> json(String.valueOf(entry.getKey())) + ":" + json(entry.getValue()))
                    .collect(Collectors.joining(",", "{", "}"));
            case Number number -> number.toString();
            default -> "\"" + value.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        };
    }
}
