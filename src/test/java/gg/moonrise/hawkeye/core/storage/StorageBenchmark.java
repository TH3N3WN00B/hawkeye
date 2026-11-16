package gg.moonrise.hawkeye.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import gg.moonrise.hawkeye.config.file.ArchiveSettings;
import gg.moonrise.hawkeye.core.queue.IdAssigner;
import gg.moonrise.hawkeye.core.repository.ActorRepository;
import gg.moonrise.hawkeye.core.repository.DictionaryRepository;
import gg.moonrise.hawkeye.core.repository.EventRepository;
import gg.moonrise.hawkeye.core.sql.Database;
import gg.moonrise.hawkeye.core.sql.SqliteHarness;
import gg.moonrise.hawkeye.entity.event.PendingEvent;
import gg.moonrise.hawkeye.entity.server.ServerNode;
import gg.moonrise.hawkeye.util.EventIds;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

/**
 * Opt-in full-scale storage benchmark on SQLite: a month of a busy server ({@link SyntheticServer},
 * every action) numbered on the one-second grid ({@link IdAssigner}) and written through {@link EventStore}
 * in the writer's batches, archived as the plugin's archiver does with the default settings (every
 * minute, segments of 262,144 settled events, smaller ones after 24 hours), then measured: the
 * database file as a running server would have it, the same file compacted, the file with everything
 * archived, and a hot-only sample for the cost of never archiving. Run with
 * {@code HAWKEYE_BENCH_EVENTS=100000000 HAWKEYE_BENCH_DIR=<folder on disk>}; the report is
 * {@code report.json} in that folder and progress goes to {@code progress.log}.
 */
@Slf4j
public class StorageBenchmark {

    private static final long START = EventIds.EPOCH_MILLIS + Duration.ofDays(600).toMillis();
    private static final int WRITER_BATCH = 2_000;
    private static final Duration ARCHIVE_INTERVAL = Duration.ofMinutes(1);
    private static final ArchiveSettings ARCHIVE = new ArchiveSettings();
    private static final long HOT_SAMPLE = 5_000_000;
    private static final long SEED = 26_03;
    private static final int QUEUED_BATCHES = 16;

    private Path folder;

    @Test
    public void aMonthOfABusyServer() throws IOException, SQLException, InterruptedException, ExecutionException {
        String configured = System.getenv("HAWKEYE_BENCH_EVENTS");
        String directory = System.getenv("HAWKEYE_BENCH_DIR");
        assumeTrue(configured != null && !configured.isBlank() && directory != null && !directory.isBlank(),
                "HAWKEYE_BENCH_EVENTS and HAWKEYE_BENCH_DIR are not set");
        long events = Long.parseLong(configured.replace("_", ""));
        Duration span = Duration.ofDays(Long.parseLong(System.getenv().getOrDefault("HAWKEYE_BENCH_DAYS", "30")));
        folder = Path.of(directory);
        Files.createDirectories(folder);
        Files.deleteIfExists(folder.resolve("progress.log"));
        double meanGap = span.toMillis() / (double) events;
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("events", events);
        report.put("spanDays", span.toDays());
        report.put("hotSample", hotSample(Math.min(HOT_SAMPLE, events), meanGap));
        report.putAll(fullRun(events, meanGap));
        Files.writeString(folder.resolve("report.json"), json(report), StandardCharsets.UTF_8);
        progress("done");
    }

    /** The first events of the same stream with archiving off: what every event costs in the hot tier. */
    private @NotNull Map<String, Object> hotSample(long events, double meanGap) throws IOException, SQLException {
        Path sampleFolder = fresh("hot-sample");
        Map<String, Object> result = new LinkedHashMap<>();
        try (SqliteHarness harness = SqliteHarness.open(sampleFolder)) {
            ServerNode node = harness.register("survival", "survival");
            EventStore store = store();
            SyntheticServer server = new SyntheticServer(SEED, START, meanGap);
            IdAssigner ids = new IdAssigner();
            List<PendingEvent> batch = new ArrayList<>(WRITER_BATCH);
            long began = System.nanoTime();
            for (long i = 0; i < events; i++) {
                batch.add(server.next());
                if (batch.size() == WRITER_BATCH) {
                    write(store, harness, node, ids, batch, false);
                    batch.clear();
                }
            }
            write(store, harness, node, ids, batch, true);
            result.put("events", events);
            result.put("insertSeconds", seconds(began));
            result.put("file", fileStats(harness.database(), sampleFolder));
            result.put("tables", tableBytes(harness.database()));
        }
        deleteFolder(sampleFolder);
        progress("hot sample done: " + result);
        return result;
    }

    private @NotNull Map<String, Object> fullRun(long events, double meanGap)
            throws IOException, SQLException, InterruptedException, ExecutionException {
        Path runFolder = fresh("run");
        Map<String, Object> result = new LinkedHashMap<>();
        try (SqliteHarness harness = SqliteHarness.open(runFolder)) {
            ServerNode node = harness.register("survival", "survival");
            EventStore store = store();
            Archive archive = archive();
            IdAssigner ids = new IdAssigner();
            SyntheticServer server = new SyntheticServer(SEED, START, meanGap);
            long nextArchive = START + ARCHIVE_INTERVAL.toMillis();
            long insertNanos = 0;
            long archiveNanos = 0;
            long archived = 0;
            long written = 0;
            long nextReport = 5_000_000;
            long lastMillis = START;
            long began = System.nanoTime();
            // On a server, events are made on the game threads and only written by the writer thread.
            BlockingQueue<List<PendingEvent>> queue = new ArrayBlockingQueue<>(QUEUED_BATCHES);
            try (ExecutorService generator = Executors.newSingleThreadExecutor()) {
                CompletableFuture<Void> produced = CompletableFuture.runAsync(() -> produce(server, events, queue),
                        generator);
                while (written < events) {
                    List<PendingEvent> batch = queue.poll(1, TimeUnit.SECONDS);
                    if (batch == null) {
                        if (produced.isCompletedExceptionally()) {
                            produced.get();
                        }
                        continue;
                    }
                    long write = System.nanoTime();
                    lastMillis = batch.getLast().epochMillis();
                    written += batch.size();
                    write(store, harness, node, ids, batch, written >= events);
                    insertNanos += System.nanoTime() - write;
                    while (lastMillis >= nextArchive) {
                        long pass = System.nanoTime();
                        archived += archiveUntil(archive, harness, nextArchive);
                        archiveNanos += System.nanoTime() - pass;
                        nextArchive += ARCHIVE_INTERVAL.toMillis();
                    }
                    if (written >= nextReport) {
                        nextReport += 5_000_000;
                        progress(String.format("%,d events, %,d archived, %.0f s (insert %.0f s, archive %.0f s), "
                                + "file %,d bytes", written, archived, seconds(began), insertNanos / 1e9,
                                archiveNanos / 1e9, Files.size(runFolder.resolve("hawkeye.db"))));
                    }
                }
                produced.get();
            }
            result.put("generated", server.counts());
            result.put("mobEntityPayloadBytes", server.mobEntityBytes());
            result.put("lastEvent", Instant.ofEpochMilli(lastMillis).toString());
            result.put("insertSeconds", insertNanos / 1e9);
            result.put("archiveSeconds", archiveNanos / 1e9);
            result.put("totalSeconds", seconds(began));
            result.put("archivedWhileRunning", archived);
            Database database = harness.database();
            assertEquals(events, rows(database), "every event is stored exactly once");
            result.put("steady", tiers(database, runFolder, "steady"));
            progress("steady state measured: " + result.get("steady"));
            long lastPass = System.nanoTime();
            archiveUntil(archive, harness, Long.MAX_VALUE / 2);
            result.put("finalArchiveSeconds", seconds(lastPass));
            result.put("allArchived", tiers(database, runFolder, "all-archived"));
        }
        deleteFolder(runFolder);
        return result;
    }

    /** Fills the queue with the server's events in writer batches. */
    private static void produce(@NotNull SyntheticServer server, long events,
            @NotNull BlockingQueue<List<PendingEvent>> queue) {
        try {
            for (long made = 0; made < events; ) {
                int size = (int) Math.min(WRITER_BATCH, events - made);
                List<PendingEvent> batch = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    batch.add(server.next());
                }
                made += size;
                queue.put(batch);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("the generator was interrupted", e);
        }
    }

    /** The file, its tiers and a compacted copy of it. */
    private @NotNull Map<String, Object> tiers(@NotNull Database database, @NotNull Path runFolder,
            @NotNull String label) throws IOException, SQLException {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("file", fileStats(database, runFolder));
        result.put("tables", tableBytes(database));
        result.put("hotEvents", count(database, "SELECT COUNT(*) FROM " + database.tables().event()));
        result.put("segments", count(database, "SELECT COUNT(*) FROM " + database.tables().segment()));
        result.put("archivedEvents", count(database, "SELECT COALESCE(SUM(events), 0) FROM "
                + database.tables().segment()));
        Path compacted = runFolder.resolve(label + "-vacuumed.db");
        Files.deleteIfExists(compacted);
        long began = System.nanoTime();
        database.read(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("VACUUM INTO '" + compacted.toAbsolutePath().toString().replace("'", "''") + "'");
            }
            return null;
        });
        result.put("vacuumedBytes", Files.size(compacted));
        result.put("vacuumSeconds", seconds(began));
        Files.delete(compacted);
        return result;
    }

    /** One archiver run at a moment of the simulated month: passes until one packs nothing. */
    private long archiveUntil(@NotNull Archive archive, @NotNull SqliteHarness harness, long nowMillis)
            throws SQLException {
        long total = 0;
        int moved;
        do {
            moved = archive.compact(harness.shard(), ARCHIVE, nowMillis);
            total += moved;
        } while (moved > 0);
        return total;
    }

    /** Numbers a captured batch as the writer does and writes what has been numbered so far. */
    private static void write(@NotNull EventStore store, @NotNull SqliteHarness harness, @NotNull ServerNode node,
            @NotNull IdAssigner ids, @NotNull List<PendingEvent> captured, boolean last) throws SQLException {
        ids.add(captured);
        long now = captured.isEmpty() ? 0 : captured.getLast().epochMillis();
        List<PendingEvent> numbered = ids.take(now, last);
        for (int from = 0; from < numbered.size(); from += WRITER_BATCH) {
            store.write(harness.shard(), node, "survival",
                    numbered.subList(from, Math.min(numbered.size(), from + WRITER_BATCH)));
        }
    }

    /** The file on disk after a checkpoint, with its page accounting. */
    private static @NotNull Map<String, Object> fileStats(@NotNull Database database, @NotNull Path databaseFolder)
            throws IOException, SQLException {
        Map<String, Object> result = new LinkedHashMap<>();
        database.read(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            }
            result.put("pageSize", pragma(connection, "page_size"));
            result.put("pages", pragma(connection, "page_count"));
            result.put("freePages", pragma(connection, "freelist_count"));
            return null;
        });
        result.put("bytes", Files.size(databaseFolder.resolve("hawkeye.db")));
        Path wal = databaseFolder.resolve("hawkeye.db-wal");
        result.put("walBytes", Files.exists(wal) ? Files.size(wal) : 0L);
        return result;
    }

    /** Bytes per table and index, from SQLite's {@code dbstat} table; empty when the build lacks it. */
    private static @NotNull Map<String, Object> tableBytes(@NotNull Database database) throws SQLException {
        Map<String, Object> result = new LinkedHashMap<>();
        database.read(connection -> {
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT name, SUM(pgsize) FROM dbstat GROUP BY name "
                         + "ORDER BY SUM(pgsize) DESC")) {
                while (rows.next()) {
                    result.put(rows.getString(1), rows.getLong(2));
                }
            } catch (SQLException e) {
                log.warn("dbstat is not available", e);
            }
            return null;
        });
        return result;
    }

    private static long rows(@NotNull Database database) throws SQLException {
        return count(database, "SELECT COUNT(*) FROM " + database.tables().event())
                + count(database, "SELECT COALESCE(SUM(events), 0) FROM " + database.tables().segment());
    }

    private static long count(@NotNull Database database, @NotNull String sql) throws SQLException {
        return database.read(connection -> {
            try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
                rows.next();
                return rows.getLong(1);
            }
        });
    }

    private static long pragma(@NotNull Connection connection, @NotNull String name) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("PRAGMA " + name)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static @NotNull EventStore store() {
        return new EventStore(new EventRepository(), new QueryResolver(new DictionaryRepository(),
                new ActorRepository()), archive());
    }

    private static @NotNull Archive archive() {
        return Archives.create();
    }

    private @NotNull Path fresh(@NotNull String name) throws IOException {
        Path path = folder.resolve(name);
        deleteFolder(path);
        return Files.createDirectories(path);
    }

    private static void deleteFolder(@NotNull Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var files = Files.list(path)) {
            for (Path file : files.toList()) {
                Files.delete(file);
            }
        }
        Files.delete(path);
    }

    private void progress(@NotNull String line) throws IOException {
        log.info(line);
        Files.writeString(folder.resolve("progress.log"), Instant.now() + " " + line + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static double seconds(long beganNanos) {
        return (System.nanoTime() - beganNanos) / 1e9;
    }

    /** A small JSON writer for the report's maps, lists, numbers, strings and action counts. */
    private static @NotNull String json(Object value) {
        return switch (value) {
            case null -> "null";
            case Map<?, ?> map -> map.entrySet().stream()
                    .map(entry -> json(String.valueOf(entry.getKey())) + ":" + json(entry.getValue()))
                    .collect(Collectors.joining(",", "{", "}"));
            case List<?> list -> list.stream().map(StorageBenchmark::json).collect(Collectors.joining(",", "[", "]"));
            case SyntheticServer.ActionCount count -> json(Map.of("action", count.action().name(), "events",
                    count.events(), "payloads", count.payloads(), "payloadBytes", count.payloadBytes()));
            case Number number -> number.toString();
            case Boolean bool -> bool.toString();
            default -> "\"" + value.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        };
    }
}
