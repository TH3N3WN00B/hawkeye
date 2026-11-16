package gg.moonrise.hawkeye.core.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.config.file.ArchiveSettings;
import gg.moonrise.hawkeye.core.queue.IdAssigner;
import gg.moonrise.hawkeye.core.repository.ActorRepository;
import gg.moonrise.hawkeye.core.repository.DictionaryRepository;
import gg.moonrise.hawkeye.core.repository.EventFilter;
import gg.moonrise.hawkeye.core.repository.EventRepository;
import gg.moonrise.hawkeye.core.repository.SegmentRepository;
import gg.moonrise.hawkeye.core.repository.SegmentRow;
import gg.moonrise.hawkeye.core.repository.segment.SegmentHeader;
import gg.moonrise.hawkeye.core.repository.segment.SharedFrame;
import gg.moonrise.hawkeye.core.sql.SqliteHarness;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.hawkeye.entity.event.PendingEvent;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.entity.query.Area;
import gg.moonrise.hawkeye.entity.query.LookupQuery;
import gg.moonrise.hawkeye.entity.query.RollbackState;
import gg.moonrise.hawkeye.entity.query.ServerScope;
import gg.moonrise.hawkeye.entity.query.TimeWindow;
import gg.moonrise.hawkeye.entity.server.ServerNode;
import gg.moonrise.hawkeye.entity.server.WorldKey;
import gg.moonrise.hawkeye.util.BlockPositions;
import gg.moonrise.hawkeye.util.EventIds;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Packing moves events into segments without changing what any read returns. The archived harness
 * holds events in the hot table and in segments whose id ranges overlap (late events packed after
 * their neighbours); a reference harness holds the same events in the hot table only.
 */
public class ArchiveTest {

    private static final long T0 = EventIds.EPOCH_MILLIS + 86_400_000L * 400;
    private static final long STEP = 7_000;
    private static final int EVENTS = 4_000;
    private static final long END = T0 + EVENTS * STEP;
    private static final ShardScope ALL = new ShardScope.All();
    private static final ArchiveSettings SEGMENTS_OF_500 = new ArchiveSettings().withPacking(500, 60, 1);

    @TempDir
    private Path folder;

    private SqliteHarness harness;
    private SqliteHarness reference;
    private EventStore store;
    private EventStore referenceStore;
    private Archive archive;
    private ServerNode node;
    private ServerNode referenceNode;
    private final List<Actor> actors = new ArrayList<>();
    private final List<PendingEvent> events = new ArrayList<>();

    @BeforeEach
    public void setUp() throws SQLException {
        harness = SqliteHarness.open(folder.resolve("archived"));
        reference = SqliteHarness.open(folder.resolve("reference"));
        archive = new Archive(new EventRepository(), new SegmentRepository(), new SegmentCache());
        store = new EventStore(new EventRepository(), new QueryResolver(new DictionaryRepository(),
                new ActorRepository()), archive);
        referenceStore = new EventStore(new EventRepository(), new QueryResolver(new DictionaryRepository(),
                new ActorRepository()), new Archive(new EventRepository(), new SegmentRepository(),
                new SegmentCache()));
        node = harness.register("survival", "survival");
        referenceNode = reference.register("survival", "survival");
        for (int i = 0; i < 6; i++) {
            actors.add(Actor.player(new UUID(0, i + 1), "player" + i));
        }
        Random random = new Random(3);
        for (int i = 0; i < EVENTS; i++) {
            events.add(event(random, T0 + i * STEP));
        }
    }

    @AfterEach
    public void tearDown() {
        harness.close();
        reference.close();
    }

    @Test
    public void readsAreIdenticalAcrossTiersAndOverlappingSegments() throws SQLException {
        archiveInTwoWaves();
        assertEquals(6, archive.totals(harness.shard()).segments());
        assertEquals(3_000, archive.totals(harness.shard()).events());
        assertTrue(overlapping(), "late events make segments with overlapping id ranges");

        List<LookupQuery> queries = queries();
        for (int i = 0; i < queries.size(); i++) {
            LookupQuery query = queries.get(i);
            List<Long> expected = pages(referenceStore, reference, query, true);
            assertTrue(expected.size() > 0, "query " + i + " matches something");
            assertEquals(expected.size(), new HashSet<>(expected).size(), "query " + i + " has no repeated id");
            assertEquals(expected, pages(store, harness, query, true), "newest-first pages of query " + i);
            assertEquals(expected.reversed(), pages(store, harness, query, false), "oldest-first pages of query " + i);
            assertEquals(expected.size(), store.count(harness.shard(), query, ALL), "count of query " + i);
        }
        List<String> all = describe(referenceStore.find(reference.shard(), queries.getFirst(), ALL, 0, 10_000, true));
        assertEquals(all, describe(store.find(harness.shard(), queries.getFirst(), ALL, 0, 10_000, true)),
                "every column of every event survives packing");
    }

    @Test
    public void rollbackFlagsOfArchivedEventsAreSetClearedAndSeen() throws SQLException {
        archiveInTwoWaves();
        LookupQuery box = queries().get(2);
        List<Long> ids = store.find(harness.shard(), box, ALL, 0, 10_000, false).stream().map(StoredEvent::id).toList();
        LookupQuery rolledBack = box.withRollbackState(RollbackState.ROLLED_BACK);

        assertEquals(ids.size(), store.setRolledBack(harness.shard(), ids, true));
        assertEquals(ids.size(), store.count(harness.shard(), rolledBack, ALL));
        assertTrue(store.find(harness.shard(), box, ALL, 0, 10_000, true).stream().allMatch(StoredEvent::rolledBack));
        assertTrue(flagRows() > 0);
        assertEquals(0, store.count(harness.shard(), box.withRollbackState(RollbackState.ACTIVE), ALL));

        store.setRolledBack(harness.shard(), ids, false);
        assertEquals(0, store.count(harness.shard(), rolledBack, ALL));
        assertEquals(0, flagRows(), "an empty set deletes the flag row");

        List<Long> half = ids.subList(0, ids.size() / 2);
        store.setRolledBack(harness.shard(), half, true);
        assertEquals(half, store.find(harness.shard(), rolledBack, ALL, 0, 10_000, false).stream()
                .map(StoredEvent::id).toList());
    }

    @Test
    public void purgeDeletesWholeSegmentsAndRewritesStraddlingOnesWithTheirFlags() throws SQLException {
        archiveInTwoWaves();
        LookupQuery everything = LookupQuery.at(new Area.Everywhere());
        List<Long> flagged = store.find(harness.shard(), everything, ALL, 0, 10_000, false).stream()
                .map(StoredEvent::id).filter(id -> id % 3 == 0).toList();
        store.setRolledBack(harness.shard(), flagged, true);
        long bound = T0 + 10_500_000;
        long kept = events.stream().filter(event -> event.epochMillis() >= bound).count();

        while (store.purgeBatch(harness.shard(), bound, List.of(), 400) > 0) {
            // Purge until nothing older is left.
        }
        assertEquals(kept, store.count(harness.shard(), everything, ALL));
        assertEquals(flagged.stream().filter(id -> id >= EventIds.floor(bound)).count(),
                store.count(harness.shard(), everything.withRollbackState(RollbackState.ROLLED_BACK), ALL),
                "flags survive the rewrite");
        assertEquals(4, archive.totals(harness.shard()).segments(), "two deleted, two rewritten, two untouched");
        assertEquals(pages(referenceStore, reference, everything, true).stream()
                .filter(id -> id >= EventIds.floor(bound)).toList(), pages(store, harness, everything, true));
    }

    @Test
    public void aPurgeOfSomeWorldsRewritesTheSegmentsThatHoldThem() throws SQLException {
        archiveInTwoWaves();
        long bound = T0 + 21_000_000;
        int nether = worldId("nether");
        long kept = events.stream().filter(event -> !event.world().equals("nether") || event.epochMillis() >= bound)
                .count();

        while (store.purgeBatch(harness.shard(), bound, List.of(nether), 400) > 0) {
            // Purge until nothing older is left.
        }
        LookupQuery everything = LookupQuery.at(new Area.Everywhere());
        assertEquals(kept, store.count(harness.shard(), everything, ALL));
        assertEquals(events.stream().filter(event -> event.world().equals("world")).count(),
                store.count(harness.shard(), LookupQuery.at(new Area.World("world")), ALL), "other worlds stay");
        assertEquals(0, store.purgeBatch(harness.shard(), bound, List.of(nether), 400), "a second run finds nothing");
    }

    @Test
    public void packsFullSegmentsOfSettledEventsAndShortOnesOnlyWhenOld() throws SQLException {
        long now = T0 + 10 * 3_600_000L;
        List<PendingEvent> batch = new ArrayList<>();
        Random random = new Random(5);
        for (int i = 0; i < 600; i++) {
            // 550 events older than the settle time, then 50 newer ones.
            long millis = i < 550 ? now - 600_000 + i * 900L : now - 50_000 + (i - 550) * 900L;
            batch.add(event(random, millis));
        }
        store.write(harness.shard(), node, "survival", batch.subList(0, 100));

        assertEquals(0, archive.compact(harness.shard(), SEGMENTS_OF_500, now), "too few and too young");
        store.write(harness.shard(), node, "survival", batch.subList(100, 600));
        assertEquals(500, archive.compact(harness.shard(), SEGMENTS_OF_500, now), "one full segment");
        assertEquals(0, archive.compact(harness.shard(), SEGMENTS_OF_500, now), "50 settled, 50 not: wait");
        assertEquals(0, archive.compact(harness.shard(), SEGMENTS_OF_500, now + 3_000_000), "still not an hour old");
        long later = now + 4 * 3_600_000L;
        assertEquals(100, archive.compact(harness.shard(), SEGMENTS_OF_500, later), "old enough: a short segment");
        assertEquals(0, archive.compact(harness.shard(), SEGMENTS_OF_500, later));
        assertEquals(600, archive.totals(harness.shard()).events());

        store.write(harness.shard(), node, "survival", List.of(event(random, later - 30_000)));
        assertEquals(0, archive.compact(harness.shard(), SEGMENTS_OF_500, later), "not settled yet");
        assertEquals(1, archive.compact(harness.shard(), SEGMENTS_OF_500, later + 3 * 3_600_000L));
    }

    @Test
    public void writeArchivedPacksTheSameBytesAsWritingHotAndCompacting() throws SQLException {
        List<PendingEvent> batch = events.subList(0, 3_000);
        store.write(harness.shard(), node, "survival", batch);
        assertEquals(3_000, archive.compact(harness.shard(), new ArchiveSettings(), END + 86_400_000L));
        // The same order, so both databases intern the same dictionary ids; the frames are compressed on a pool.
        try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
            assertEquals(3_000, referenceStore.writeArchived(reference.shard(), referenceNode, "survival", batch,
                    pool));
        }
        byte[][] packed = segmentBytes(harness);
        byte[][] imported = segmentBytes(reference);
        assertArrayEquals(packed[0], imported[0], "header");
        assertArrayEquals(packed[1], imported[1], "body");
        assertEquals(3_000, referenceStore.count(reference.shard(), LookupQuery.at(new Area.Everywhere()), ALL));
    }

    @Test
    public void idsNumberedByTheWriterNeedNoRankFrame() throws SQLException {
        IdAssigner assigner = new IdAssigner();
        Random random = new Random(11);
        List<PendingEvent> captured = new ArrayList<>();
        for (int i = 0; i < 5_000; i++) {
            // Bursts of up to 40 events per second, in two worlds, with chat between the block changes.
            captured.add(event(random, T0 + i * 37L, EventIds.MAX_SEQUENCE & i));
        }
        assigner.add(captured);
        List<PendingEvent> numbered = assigner.take(END, true);
        store.write(harness.shard(), node, "survival", numbered);
        assertEquals(5_000, archive.compact(harness.shard(), new ArchiveSettings(), END + 86_400_000L));
        SegmentHeader header = SegmentHeader.parse(segmentBytes(harness)[0]);
        assertEquals(0, header.frameLength(SharedFrame.RANK), "the writer's ranks follow from the stored order");
        assertTrue(header.nodes().size() == 1 && header.worlds().size() == 2, header.worlds().toString());

        store.write(reference.shard(), referenceNode, "survival", captured);
        assertEquals(5_000, archive.compact(reference.shard(), new ArchiveSettings(), END + 86_400_000L));
        SegmentHeader unordered = SegmentHeader.parse(segmentBytes(reference)[0]);
        assertTrue(unordered.frameLength(SharedFrame.RANK) > 0, "capture stamps do need ranks");
    }

    /**
     * Even-numbered events are written and packed first (four segments); the odd ones arrive late and
     * half of them are packed (two segments overlapping the first four); the rest stays hot.
     */
    private void archiveInTwoWaves() throws SQLException {
        List<PendingEvent> even = new ArrayList<>();
        List<PendingEvent> odd = new ArrayList<>();
        for (int i = 0; i < events.size(); i++) {
            (i % 2 == 0 ? even : odd).add(events.get(i));
        }
        referenceStore.write(reference.shard(), referenceNode, "survival", events);
        store.write(harness.shard(), node, "survival", even);
        int packed = 0;
        int pass;
        while ((pass = archive.compact(harness.shard(), SEGMENTS_OF_500, END + 120_000)) > 0) {
            packed += pass;
        }
        assertEquals(2_000, packed);
        store.write(harness.shard(), node, "survival", odd);
        long halfway = T0 + EVENTS * STEP / 2;
        packed = 0;
        while ((pass = archive.compact(harness.shard(), SEGMENTS_OF_500, halfway + 60_000)) > 0) {
            packed += pass;
        }
        assertEquals(1_000, packed);
    }

    private boolean overlapping() throws SQLException {
        List<SegmentRow> segments = harness.database().read(connection -> new SegmentRepository().candidates(
                connection, harness.database().sql(), EventFilter.everything(0, Long.MAX_VALUE), 0, null, 100, false));
        for (int i = 1; i < segments.size(); i++) {
            if (segments.get(i).firstId() < segments.get(i - 1).lastId()) {
                return true;
            }
        }
        return false;
    }

    private List<LookupQuery> queries() {
        PendingEvent sample = events.stream().filter(event -> event.action() == Action.BLOCK_PLACE).skip(40)
                .findFirst().orElseThrow();
        Area.Block block = new Area.Block(sample.world(), BlockPositions.x(sample.position()),
                BlockPositions.y(sample.position()), BlockPositions.z(sample.position()));
        List<LookupQuery> queries = new ArrayList<>();
        queries.add(LookupQuery.at(new Area.Everywhere()));
        queries.add(LookupQuery.at(block));
        queries.add(query(new Area.Box("world", -15, 60, -20, 10, 66, 5), Set.of(), Set.of(), Set.of()));
        queries.add(query(new Area.Everywhere(), Set.of("player2"), Set.of(), Set.of()));
        queries.add(query(new Area.World("nether"), Set.of(), Set.of(Action.CONTAINER_DEPOSIT), Set.of()));
        queries.add(query(new Area.Everywhere(), Set.of(), Set.of(Action.CHAT), Set.of()));
        queries.add(query(new Area.Everywhere(), Set.of("player1", "player4"), Set.of(), Set.of("minecraft:oak_log")));
        queries.add(query(new Area.Box("nether", -40, 60, -40, 40, 70, 40), Set.of("player5"),
                Set.of(Action.BLOCK_BREAK), Set.of()).withTime(new TimeWindow(T0 + 3_000_000, T0 + 20_000_000)));
        queries.add(LookupQuery.at(new Area.Everywhere()).withTime(new TimeWindow(T0 + 13_000_000, T0 + 15_000_000)));
        return queries;
    }

    private static LookupQuery query(Area area, Set<String> actorNames, Set<Action> actions, Set<String> types) {
        return new LookupQuery(new ServerScope.Local(), area, TimeWindow.ALL, actorNames, Set.of(), actions, types,
                Set.of(), RollbackState.ANY);
    }

    private PendingEvent event(Random random, long millis) {
        return event(random, millis, 0);
    }

    private PendingEvent event(Random random, long millis, int sequence) {
        int roll = random.nextInt(10);
        Action action = roll == 0 ? Action.CHAT : roll < 3 ? Action.CONTAINER_DEPOSIT
                : random.nextBoolean() ? Action.BLOCK_PLACE : Action.BLOCK_BREAK;
        Actor actor = actors.get(random.nextInt(actors.size()));
        if (action == Action.CHAT) {
            return new PendingEvent(EventIds.stamp(millis, sequence), "world", 0, actor, action, "", null, 0,
                    new byte[] {1, 'h', 'i', (byte) random.nextInt(100)});
        }
        String subject = action == Action.CONTAINER_DEPOSIT ? "minecraft:diamond"
                : random.nextBoolean() ? "minecraft:stone" : "minecraft:oak_log[axis=y]";
        return new PendingEvent(EventIds.stamp(millis, sequence), random.nextBoolean() ? "world" : "nether",
                BlockPositions.pack(random.nextInt(80) - 40, 60 + random.nextInt(10), random.nextInt(80) - 40), actor,
                action, subject, action == Action.CONTAINER_DEPOSIT ? null : "minecraft:air",
                action == Action.CONTAINER_DEPOSIT ? 1 + random.nextInt(64) : 0,
                random.nextInt(25) == 0 ? new byte[] {2, (byte) random.nextInt(), 7} : null);
    }

    /** Every id the query returns, read page by page with a keyset cursor. */
    private static List<Long> pages(EventStore eventStore, SqliteHarness on, LookupQuery query, boolean newestFirst)
            throws SQLException {
        List<Long> ids = new ArrayList<>();
        long cursor = 0;
        while (true) {
            List<StoredEvent> page = eventStore.find(on.shard(), query, ALL, cursor, 37, newestFirst);
            page.forEach(event -> ids.add(event.id()));
            if (page.size() < 37) {
                return ids;
            }
            cursor = page.getLast().id();
        }
    }

    private static List<String> describe(List<StoredEvent> found) {
        return found.stream().map(event -> event.id() + " " + event.server() + " " + event.world() + " "
                + event.position() + " " + event.actor() + " " + event.action() + " " + event.subject() + " "
                + event.secondary() + " " + event.amount() + " " + event.rolledBack() + " "
                + Arrays.toString(event.data())).toList();
    }

    private int worldId(String name) throws SQLException {
        Map<Integer, WorldKey> worlds = store.worlds(harness.shard(), List.of("survival"));
        return worlds.entrySet().stream().filter(entry -> entry.getValue().name().equals(name)).findFirst()
                .orElseThrow().getKey();
    }

    private long flagRows() throws SQLException {
        return harness.database().read(connection -> {
            try (Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM "
                            + harness.database().tables().segmentFlag())) {
                rows.next();
                return rows.getLong(1);
            }
        });
    }

    /** The header and body of the shard's only segment. */
    private static byte[][] segmentBytes(SqliteHarness on) throws SQLException {
        return on.database().read(connection -> {
            try (Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery("SELECT header, body FROM "
                            + on.database().tables().segment())) {
                assertTrue(rows.next(), "a segment exists");
                byte[][] bytes = {rows.getBytes(1), rows.getBytes(2)};
                assertTrue(!rows.next(), "only one segment");
                return bytes;
            }
        });
    }
}
