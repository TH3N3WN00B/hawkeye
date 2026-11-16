package gg.moonrise.hawkeye.core.storage.replay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import gg.moonrise.hawkeye.config.file.ArchiveSettings;
import gg.moonrise.hawkeye.core.queue.IdAssigner;
import gg.moonrise.hawkeye.core.repository.ActorRepository;
import gg.moonrise.hawkeye.core.repository.DictionaryRepository;
import gg.moonrise.hawkeye.core.repository.EventRepository;
import gg.moonrise.hawkeye.core.repository.SegmentRepository;
import gg.moonrise.hawkeye.core.sql.Database;
import gg.moonrise.hawkeye.core.sql.SqliteHarness;
import gg.moonrise.hawkeye.core.storage.Archive;
import gg.moonrise.hawkeye.core.storage.EventStore;
import gg.moonrise.hawkeye.core.storage.QueryResolver;
import gg.moonrise.hawkeye.core.storage.SegmentCache;
import gg.moonrise.hawkeye.core.storage.ShardScope;
import gg.moonrise.hawkeye.entity.actor.ActorKind;
import gg.moonrise.hawkeye.entity.event.PendingEvent;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.entity.query.Area;
import gg.moonrise.hawkeye.entity.query.LookupQuery;
import gg.moonrise.hawkeye.entity.query.RollbackState;
import gg.moonrise.hawkeye.entity.query.ServerScope;
import gg.moonrise.hawkeye.entity.query.TimeWindow;
import gg.moonrise.hawkeye.entity.server.ServerNode;
import gg.moonrise.hawkeye.util.BlockPositions;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

/**
 * Opt-in: replays an exported CoreProtect history ({@code scripts/bench/reference-export}) as the
 * events Hawkeye would have captured, through the writer's id numbering and the archive, and measures
 * the SQLite file against what the same rows cost in the CoreProtect database.
 *
 * <p>{@code HAWKEYE_REPLAY_DUMP} is the export folder and {@code HAWKEYE_BENCH_DIR} a folder on disk
 * for the database and {@code replay-report.json}. Optional: {@code HAWKEYE_REPLAY_DAYS}
 * ({@code first-last} day numbers, default every day), {@code HAWKEYE_REPLAY_MODE} ({@code bulk},
 * the default, packs every {@code archive.segment-events} events straight into a segment on a thread
 * pool; {@code live} writes every event to the hot table and lets the archiver pack it, as a server
 * does: the same segments, byte for byte, at about a tenth of the speed), {@code HAWKEYE_REPLAY_THREADS}
 * {@code HAWKEYE_REPLAY_KEEP=1} to keep the database and {@code HAWKEYE_REPLAY_RESUME=1} to add the
 * chosen days to a kept database (a long history in several runs). {@link #lookupsOnAKeptDatabase()}
 * ({@code HAWKEYE_REPLAY_LOOKUPS=1}) times the lookups again on a kept database, without replaying,
 * and writes {@code lookup-report.json}. Give the test JVM memory with
 * {@code HAWKEYE_TEST_HEAP=3g}, and point {@code SQLITE_TMPDIR} at a disk.
 */
public class ReplayBenchmark {

    private static final ArchiveSettings ARCHIVE = new ArchiveSettings();
    private static final String SCOPE = "smp";
    private static final int WRITER_BATCH = 2_000;
    private static final long PROFILED_EVENTS = 5_000_000;
    private static final long PROGRESS_EVERY = 20_000_000;
    private static final List<PendingEvent> END = List.of();

    private Path folder;

    @Test
    public void aRealHistoryStoredByHawkeye() throws IOException, SQLException, InterruptedException,
            ExecutionException {
        String export = System.getenv("HAWKEYE_REPLAY_DUMP");
        String directory = System.getenv("HAWKEYE_BENCH_DIR");
        assumeTrue(export != null && !export.isBlank() && directory != null && !directory.isBlank(),
                "HAWKEYE_REPLAY_DUMP and HAWKEYE_BENCH_DIR are not set");
        folder = Path.of(directory);
        Files.createDirectories(folder);
        if (!"1".equals(System.getenv("HAWKEYE_REPLAY_RESUME"))) {
            Files.deleteIfExists(folder.resolve("replay-progress.log"));
        }
        ReferenceDump dump = new ReferenceDump(Path.of(export));
        List<Integer> days = days(dump);
        boolean live = "live".equalsIgnoreCase(System.getenv("HAWKEYE_REPLAY_MODE"));
        int threads = Integer.parseInt(System.getenv().getOrDefault("HAWKEYE_REPLAY_THREADS",
                String.valueOf(Math.max(1, Runtime.getRuntime().availableProcessors() - 1))));
        boolean resume = "1".equals(System.getenv("HAWKEYE_REPLAY_RESUME"));
        Path runFolder = folder.resolve("replay-run");
        if (!resume) {
            deleteFolder(runFolder);
        }
        Files.createDirectories(runFolder);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("mode", live ? "live" : "bulk");
        report.put("firstDay", days.getFirst());
        report.put("lastDay", days.getLast());
        report.put("days", days.size());
        report.put("segmentEvents", ARCHIVE.segmentEvents());
        report.put("reference", reference(Path.of(export), days));
        try (SqliteHarness harness = SqliteHarness.open(runFolder)) {
            ServerNode node = harness.register(SCOPE, SCOPE);
            Archive archive = new Archive(new EventRepository(), new SegmentRepository(), new SegmentCache());
            EventStore store = new EventStore(new EventRepository(), new QueryResolver(new DictionaryRepository(),
                    new ActorRepository()), archive);
            long before = stored(harness.database());
            Profile profile = new Profile();
            ReferenceEvents events = new ReferenceEvents(dump);
            long began = System.nanoTime();
            long written = 0;
            long lastMillis = 0;
            long storeNanos = 0;
            long nextProgress = PROGRESS_EVERY;
            BlockingQueue<List<PendingEvent>> queue = new ArrayBlockingQueue<>(2);
            try (ExecutorService producer = Executors.newSingleThreadExecutor();
                 ExecutorService pool = Executors.newFixedThreadPool(threads)) {
                CompletableFuture<Void> produced = CompletableFuture.runAsync(
                        () -> produce(events, days, profile, queue), producer);
                while (true) {
                    List<PendingEvent> batch = queue.poll(1, TimeUnit.SECONDS);
                    if (batch == null) {
                        if (produced.isCompletedExceptionally()) {
                            produced.get();
                        }
                        continue;
                    }
                    if (batch.isEmpty()) {
                        break;
                    }
                    long start = System.nanoTime();
                    lastMillis = batch.getLast().epochMillis();
                    if (live) {
                        writeLive(store, archive, harness, node, batch);
                    } else {
                        store.writeArchived(harness.shard(), node, SCOPE, batch, pool);
                    }
                    storeNanos += System.nanoTime() - start;
                    written += batch.size();
                    if (written >= nextProgress) {
                        nextProgress += PROGRESS_EVERY;
                        progress(String.format(Locale.ROOT, "%,d events to %s, %.0f s (storing %.0f s), file %,d bytes",
                                written, Instant.ofEpochMilli(lastMillis), seconds(began), storeNanos / 1e9,
                                Files.size(runFolder.resolve("hawkeye.db"))));
                    }
                }
                produced.get();
            }
            if (live) {
                // What is left is a short last segment: packed once it is older than archive.after-hours.
                while (archive.compact(harness.shard(), ARCHIVE, Long.MAX_VALUE / 2) > 0) {
                    written += 0;
                }
            }
            Database database = harness.database();
            report.put("events", before + written);
            report.put("eventsThisRun", written);
            report.put("rolledBackRowsNotReplayed", events.rolledBack());
            report.put("lastEvent", Instant.ofEpochMilli(lastMillis).toString());
            report.put("totalSeconds", seconds(began));
            report.put("storeSeconds", storeNanos / 1e9);
            assertEquals(before + written, stored(database), "every event is stored exactly once");
            report.put("file", fileStats(database, runFolder));
            report.put("tables", tableBytes(database));
            report.put("segments", count(database, "SELECT COUNT(*) FROM " + database.tables().segment()));
            report.put("hotEvents", count(database, "SELECT COUNT(*) FROM " + database.tables().event()));
            report.put("headerBytes", count(database, "SELECT COALESCE(SUM(LENGTH(header)), 0) FROM "
                    + database.tables().segment()));
            report.put("bodyBytes", count(database, "SELECT COALESCE(SUM(LENGTH(body)), 0) FROM "
                    + database.tables().segment()));
            progress("stored: " + report.get("file"));
            Targets targets = profile.targets(lastMillis);
            targets.save(runFolder.resolve(Targets.FILE));
            report.put("lookups", lookups(store, harness, targets));
            progress("lookups: " + report.get("lookups"));
            report.put("vacuumedBytes", vacuumed(database, runFolder));
        }
        Files.writeString(folder.resolve("replay-report.json"), json(report), StandardCharsets.UTF_8);
        if (!"1".equals(System.getenv("HAWKEYE_REPLAY_KEEP"))) {
            deleteFolder(runFolder);
        }
        progress("done");
    }

    @Test
    public void lookupsOnAKeptDatabase() throws IOException, SQLException {
        String directory = System.getenv("HAWKEYE_BENCH_DIR");
        assumeTrue("1".equals(System.getenv("HAWKEYE_REPLAY_LOOKUPS")) && directory != null && !directory.isBlank(),
                "HAWKEYE_REPLAY_LOOKUPS=1 and HAWKEYE_BENCH_DIR are not set");
        folder = Path.of(directory);
        Path runFolder = folder.resolve("replay-run");
        assumeTrue(Files.exists(runFolder.resolve(Targets.FILE)), "no kept database in " + runFolder);
        Map<String, Object> report = new LinkedHashMap<>();
        try (SqliteHarness harness = SqliteHarness.open(runFolder)) {
            Archive archive = new Archive(new EventRepository(), new SegmentRepository(), new SegmentCache());
            EventStore store = new EventStore(new EventRepository(), new QueryResolver(new DictionaryRepository(),
                    new ActorRepository()), archive);
            Database database = harness.database();
            report.put("events", stored(database));
            report.put("segments", count(database, "SELECT COUNT(*) FROM " + database.tables().segment()));
            report.put("lookups", lookups(store, harness, Targets.load(runFolder.resolve(Targets.FILE))));
        }
        Files.writeString(folder.resolve("lookup-report.json"), json(report), StandardCharsets.UTF_8);
        progress("lookups on the kept database: " + report);
    }

    /** Numbers the replayed events the way the writer does and hands them on a segment at a time. */
    private static void produce(@NotNull ReferenceEvents events, @NotNull List<Integer> days, @NotNull Profile profile,
            @NotNull BlockingQueue<List<PendingEvent>> queue) {
        IdAssigner assigner = new IdAssigner();
        int segment = ARCHIVE.segmentEvents();
        List<PendingEvent> pending = new ArrayList<>(segment + 65_536);
        try {
            for (int day : days) {
                events.replay(day, (second, captured) -> {
                    assigner.add(captured);
                    pending.addAll(assigner.take((second + 1) * 1000 + IdAssigner.GRACE_MILLIS, false));
                    while (pending.size() >= segment) {
                        List<PendingEvent> head = pending.subList(0, segment);
                        List<PendingEvent> batch = new ArrayList<>(head);
                        head.clear();
                        profile.add(batch);
                        put(queue, batch);
                    }
                });
            }
            pending.addAll(assigner.take(Long.MAX_VALUE / 2, true));
            for (int from = 0; from < pending.size(); from += segment) {
                List<PendingEvent> batch = new ArrayList<>(pending.subList(from, Math.min(pending.size(), from + segment)));
                profile.add(batch);
                put(queue, batch);
            }
            put(queue, END);
        } catch (IOException e) {
            throw new IllegalStateException("the export could not be read", e);
        }
    }

    private static void put(@NotNull BlockingQueue<List<PendingEvent>> queue, @NotNull List<PendingEvent> batch) {
        try {
            queue.put(batch);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("the replay was interrupted", e);
        }
    }

    /** The server's path: writer batches into the hot table, then the archiver's passes. */
    private static void writeLive(@NotNull EventStore store, @NotNull Archive archive, @NotNull SqliteHarness harness,
            @NotNull ServerNode node, @NotNull List<PendingEvent> batch) throws SQLException {
        long settle = TimeUnit.SECONDS.toMillis(ARCHIVE.settleSeconds() + 2L);
        for (int from = 0; from < batch.size(); from += WRITER_BATCH) {
            List<PendingEvent> part = batch.subList(from, Math.min(batch.size(), from + WRITER_BATCH));
            store.write(harness.shard(), node, SCOPE, part);
            long now = part.getLast().epochMillis() + settle;
            while (archive.compact(harness.shard(), ARCHIVE, now) > 0) {
                now = part.getLast().epochMillis() + settle;
            }
        }
    }

    /** Typical lookups against the finished database: the first run and the median of three more, in ms. */
    private static @NotNull Map<String, Object> lookups(@NotNull EventStore store, @NotNull SqliteHarness harness,
            @NotNull Targets targets) throws SQLException {
        Map<String, Object> result = new LinkedHashMap<>();
        Profile.Place busy = targets.busy();
        long lastMillis = targets.lastMillis();
        result.put("busyBlock", busy.world() + " " + BlockPositions.x(busy.position()) + " "
                + BlockPositions.y(busy.position()) + " " + BlockPositions.z(busy.position()) + " (" + busy.events()
                + " of the first " + PROFILED_EVENTS + " events)");
        LookupQuery busyBlock = LookupQuery.at(block(busy));
        int x = BlockPositions.x(busy.position());
        int y = BlockPositions.y(busy.position());
        int z = BlockPositions.z(busy.position());
        LookupQuery radius = LookupQuery.at(new Area.Box(busy.world(), x - 5, Math.max(BlockPositions.MIN_Y, y - 5),
                z - 5, x + 5, Math.min(BlockPositions.MAX_Y, y + 5), z + 5));
        LookupQuery player = new LookupQuery(new ServerScope.Local(), new Area.Everywhere(), TimeWindow.ALL,
                Set.of(targets.player()), Set.of(), Set.of(), Set.of(), Set.of(), RollbackState.ANY);
        LookupQuery lastHour = LookupQuery.at(new Area.Everywhere())
                .withTime(new TimeWindow(lastMillis - TimeUnit.HOURS.toMillis(1), lastMillis));
        LookupQuery lastDayAtBlock = busyBlock.withTime(new TimeWindow(lastMillis - TimeUnit.DAYS.toMillis(1),
                lastMillis));
        ShardScope all = new ShardScope.All();
        result.put("inspect a busy block, newest 10", timed(() -> store.find(harness.shard(), busyBlock, all, 0, 10,
                true).size()));
        List<StoredEvent> firstPage = store.find(harness.shard(), busyBlock, all, 0, 10, true);
        long cursor = firstPage.isEmpty() ? 0 : firstPage.getLast().id();
        result.put("the next page of it", timed(() -> store.find(harness.shard(), busyBlock, all, cursor, 10,
                true).size()));
        result.put("inspect a block touched once in a quiet chunk, whole history", timed(() -> store.find(
                harness.shard(), LookupQuery.at(block(targets.quiet())), all, 0, 10, true).size()));
        result.put("inspect a block touched once in the busiest chunk, whole history", timed(() -> store.find(
                harness.shard(), LookupQuery.at(block(targets.quietInBusyChunk())), all, 0, 10, true).size()));
        // The worst case for a read: the chunk is in nearly every segment, the block in none of them.
        Area untouched = new Area.Block(busy.world(), x, y >= 0 ? BlockPositions.MIN_Y + 7 : BlockPositions.MAX_Y - 7, z);
        result.put("inspect a block never touched in the busiest chunk, whole history", timed(() -> store.find(
                harness.shard(), LookupQuery.at(untouched), all, 0, 10, true).size()));
        result.put("radius 5 around the busy block, newest 10", timed(() -> store.find(harness.shard(), radius, all,
                0, 10, true).size()));
        result.put("a player's newest 10 events", timed(() -> store.find(harness.shard(), player, all, 0, 10,
                true).size()));
        result.put("everything in the last hour, newest 10", timed(() -> store.find(harness.shard(), lastHour, all,
                0, 10, true).size()));
        result.put("count the busy block's last day", timed(() -> (int) Math.min(Integer.MAX_VALUE,
                store.count(harness.shard(), lastDayAtBlock, all))));
        return result;
    }

    private static @NotNull Area block(@NotNull Profile.Place place) {
        return new Area.Block(place.world(), BlockPositions.x(place.position()), BlockPositions.y(place.position()),
                BlockPositions.z(place.position()));
    }

    private static @NotNull Map<String, Object> timed(@NotNull Lookup lookup) throws SQLException {
        Map<String, Object> result = new LinkedHashMap<>();
        long start = System.nanoTime();
        int rows = lookup.run();
        result.put("firstMillis", (System.nanoTime() - start) / 1e6);
        List<Double> more = new ArrayList<>();
        for (int run = 0; run < 3; run++) {
            start = System.nanoTime();
            lookup.run();
            more.add((System.nanoTime() - start) / 1e6);
        }
        more.sort(Comparator.naturalOrder());
        result.put("againMillis", more.get(1));
        result.put("rows", rows);
        return result;
    }

    /** What the replayed days cost in the CoreProtect database, from the export's per-day accounting. */
    private static @NotNull Map<String, Object> reference(@NotNull Path export, @NotNull List<Integer> days)
            throws IOException {
        Map<String, Object> result = new LinkedHashMap<>();
        Path file = export.resolve("meta").resolve("refbytes.tsv");
        if (!Files.exists(file)) {
            return result;
        }
        long rows = 0;
        long bytes = 0;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String[] fields = line.split("\t");
            if (fields.length < 7 || !fields[0].matches("\\d+") || !days.contains(Integer.parseInt(fields[0]))) {
                continue;
            }
            rows += Long.parseLong(fields[2]);
            bytes += Long.parseLong(fields[3]) + Long.parseLong(fields[4]) + Long.parseLong(fields[5])
                    + Long.parseLong(fields[6]);
        }
        result.put("rows", rows);
        result.put("segmentBytes", bytes);
        return result;
    }

    private static @NotNull List<Integer> days(@NotNull ReferenceDump dump) throws IOException {
        List<Integer> all = dump.days();
        String range = System.getenv("HAWKEYE_REPLAY_DAYS");
        if (range == null || range.isBlank()) {
            return all;
        }
        String[] bounds = range.split("-");
        int first = Integer.parseInt(bounds[0].trim());
        int last = Integer.parseInt(bounds[bounds.length - 1].trim());
        List<Integer> chosen = all.stream().filter(day -> day >= first && day <= last).toList();
        assumeTrue(!chosen.isEmpty(), "no exported day in " + range);
        return chosen;
    }

    private static long vacuumed(@NotNull Database database, @NotNull Path runFolder) throws IOException, SQLException {
        Path compacted = runFolder.resolve("vacuumed.db");
        Files.deleteIfExists(compacted);
        database.read(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("VACUUM INTO '" + compacted.toAbsolutePath().toString().replace("'", "''") + "'");
            }
            return null;
        });
        long bytes = Files.size(compacted);
        Files.delete(compacted);
        return bytes;
    }

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
        return result;
    }

    private static @NotNull Map<String, Object> tableBytes(@NotNull Database database) throws SQLException {
        Map<String, Object> result = new LinkedHashMap<>();
        database.read(connection -> {
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT name, SUM(pgsize) FROM dbstat GROUP BY name "
                         + "ORDER BY SUM(pgsize) DESC")) {
                while (rows.next()) {
                    result.put(rows.getString(1), rows.getLong(2));
                }
            }
            return null;
        });
        return result;
    }

    private static long stored(@NotNull Database database) throws SQLException {
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

    private void progress(@NotNull String line) throws IOException {
        Files.writeString(folder.resolve("replay-progress.log"), Instant.now() + " " + line + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static void deleteFolder(@NotNull Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> files = Files.walk(path)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(file);
            }
        }
    }

    private static double seconds(long beganNanos) {
        return (System.nanoTime() - beganNanos) / 1e9;
    }

    private static @NotNull String json(Object value) {
        if (value instanceof Map<?, ?> map) {
            StringBuilder out = new StringBuilder("{");
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.append(out.length() > 1 ? "," : "").append(json(String.valueOf(entry.getKey()))).append(':')
                        .append(json(entry.getValue()));
            }
            return out.append('}').toString();
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return '"' + String.valueOf(value).replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    /** What the lookups are timed on; saved with a kept database so they can be timed again. */
    private record Targets(@NotNull Profile.Place busy, @NotNull Profile.Place quiet,
            @NotNull Profile.Place quietInBusyChunk, @NotNull String player, long lastMillis) {

        private static final String FILE = "lookup-targets.txt";

        private void save(@NotNull Path file) throws IOException {
            Files.write(file, List.of(line(busy), line(quiet), line(quietInBusyChunk), player,
                    String.valueOf(lastMillis)), StandardCharsets.UTF_8);
        }

        private static @NotNull Targets load(@NotNull Path file) throws IOException {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            return new Targets(place(lines.get(0)), place(lines.get(1)), place(lines.get(2)), lines.get(3),
                    Long.parseLong(lines.get(4)));
        }

        private static @NotNull String line(@NotNull Profile.Place place) {
            return place.world() + "\t" + place.position() + "\t" + place.events();
        }

        private static @NotNull Profile.Place place(@NotNull String line) {
            String[] fields = line.split("\t");
            return new Profile.Place(fields[0], Long.parseLong(fields[1]), Integer.parseInt(fields[2]));
        }
    }

    /** One timed lookup; returns the number of rows it found. */
    @FunctionalInterface
    private interface Lookup {

        int run() throws SQLException;
    }

    /** Where and by whom the first events happened, to pick realistic lookups. */
    private static final class Profile {

        /** A packed position without its bits inside the chunk (x & 15, z & 15, y). */
        private static final int CHUNK_SHIFT = 20;

        private final Map<String, Map<Long, int[]>> places = new HashMap<>();
        private final Map<String, int[]> players = new HashMap<>();
        private long seen;

        private synchronized void add(@NotNull List<PendingEvent> batch) {
            if (seen >= PROFILED_EVENTS) {
                return;
            }
            for (PendingEvent event : batch) {
                places.computeIfAbsent(event.world(), world -> new HashMap<>())
                        .computeIfAbsent(event.position(), position -> new int[1])[0]++;
                if (event.actor().kind() == ActorKind.PLAYER) {
                    players.computeIfAbsent(event.actor().name(), name -> new int[1])[0]++;
                }
            }
            seen += batch.size();
        }

        private synchronized @NotNull Place busiest() {
            Place best = new Place("world", 0, 0);
            for (Map.Entry<String, Map<Long, int[]>> world : places.entrySet()) {
                for (Map.Entry<Long, int[]> place : world.getValue().entrySet()) {
                    if (place.getValue()[0] > best.events()) {
                        best = new Place(world.getKey(), place.getKey(), place.getValue()[0]);
                    }
                }
            }
            return best;
        }

        /** The lookups worth timing: the busiest block, blocks touched once in a quiet and in the busiest chunk. */
        private synchronized @NotNull Targets targets(long lastMillis) {
            Place busy = busiest();
            Map<Long, int[]> chunks = new HashMap<>();
            Map<Long, int[]> inWorld = places.getOrDefault(busy.world(), Map.of());
            inWorld.forEach((position, events) -> chunks.computeIfAbsent(position >> CHUNK_SHIFT,
                    chunk -> new int[1])[0] += events[0]);
            Place quiet = busy;
            Place quietInBusyChunk = busy;
            int quietChunk = Integer.MAX_VALUE;
            for (Map.Entry<Long, int[]> place : inWorld.entrySet()) {
                if (place.getValue()[0] != 1) {
                    continue;
                }
                long position = place.getKey();
                int chunkEvents = chunks.get(position >> CHUNK_SHIFT)[0];
                if (chunkEvents < quietChunk || chunkEvents == quietChunk && position < quiet.position()) {
                    quietChunk = chunkEvents;
                    quiet = new Place(busy.world(), position, 1);
                }
                if (position >> CHUNK_SHIFT == busy.position() >> CHUNK_SHIFT
                        && (quietInBusyChunk == busy || position < quietInBusyChunk.position())) {
                    quietInBusyChunk = new Place(busy.world(), position, 1);
                }
            }
            String player = players.entrySet().stream().max(Comparator.comparingInt(entry -> entry.getValue()[0]))
                    .map(Map.Entry::getKey).orElse("nobody");
            return new Targets(busy, quiet, quietInBusyChunk, player, lastMillis);
        }

        /** A block and how many profiled events happened there. */
        private record Place(@NotNull String world, long position, int events) {
        }
    }
}
