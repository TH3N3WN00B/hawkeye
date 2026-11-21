package gg.moonrise.hawkeye.core.storage.archive;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.config.file.ArchiveSettings;
import gg.moonrise.hawkeye.core.queue.id.IdAssigner;
import gg.moonrise.hawkeye.core.repository.archive.CountingSegmentRepository;
import gg.moonrise.hawkeye.core.repository.archive.SegmentRepository;
import gg.moonrise.hawkeye.core.repository.archive.SegmentRow;
import gg.moonrise.hawkeye.core.repository.dictionary.ActorRepository;
import gg.moonrise.hawkeye.core.repository.dictionary.DictionaryRepository;
import gg.moonrise.hawkeye.core.repository.event.EventFilter;
import gg.moonrise.hawkeye.core.repository.event.EventRepository;
import gg.moonrise.hawkeye.core.repository.index.PlaceIndexRepository;
import gg.moonrise.hawkeye.core.repository.index.SegmentIndexRepository;
import gg.moonrise.hawkeye.core.sql.SqliteHarness;
import gg.moonrise.hawkeye.core.storage.event.EventStore;
import gg.moonrise.hawkeye.core.storage.event.QueryResolver;
import gg.moonrise.hawkeye.core.storage.index.SegmentIndex;
import gg.moonrise.hawkeye.core.storage.index.place.PlaceChunk;
import gg.moonrise.hawkeye.core.storage.index.place.PlaceKeys;
import gg.moonrise.hawkeye.core.storage.index.place.PlaceRegion;
import gg.moonrise.hawkeye.core.storage.segment.SegmentHeader;
import gg.moonrise.hawkeye.core.storage.segment.SharedFrame;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.hawkeye.entity.event.PendingEvent;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.entity.query.Area;
import gg.moonrise.hawkeye.entity.query.LookupQuery;
import gg.moonrise.hawkeye.entity.query.RollbackState;
import gg.moonrise.hawkeye.entity.query.ServerScope;
import gg.moonrise.hawkeye.entity.query.ShardScope;
import gg.moonrise.hawkeye.entity.query.TimeWindow;
import gg.moonrise.hawkeye.entity.server.ServerNode;
import gg.moonrise.hawkeye.entity.server.WorldKey;
import gg.moonrise.hawkeye.util.position.BlockPositions;
import gg.moonrise.hawkeye.util.event.EventIds;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
 * their neighbours); a reference harness holds the same events in the hot table only. The archived
 * harness's place index calls a chunk busy after {@link #BUSY} segments and keeps {@link #EXACT} segment
 * ids per position exactly, so the events around the origin are in busy chunks, blocks touched in many
 * segments have older buckets, and the scattered events far from the origin are in chunks of one segment.
 */
public class ArchiveTest {

    private static final long T0 = EventIds.EPOCH_MILLIS + 86_400_000L * 400;
    private static final long STEP = 7_000;
    private static final int EVENTS = 4_000;
    private static final long END = T0 + EVENTS * STEP;
    private static final ShardScope ALL = new ShardScope.All();
    private static final ArchiveSettings SEGMENTS_OF_500 = new ArchiveSettings().withPacking(500, 60, 1);
    private static final ArchiveSettings SEGMENTS_OF_50 = new ArchiveSettings().withPacking(50, 60, 1);
    private static final int BUSY = 2;
    private static final int EXACT = 4;

    @TempDir
    private Path folder;

    private SqliteHarness harness;
    private SqliteHarness reference;
    private EventStore store;
    private EventStore referenceStore;
    private Archive archive;
    private CountingSegmentRepository segments;
    private ServerNode node;
    private ServerNode referenceNode;
    private final List<Actor> actors = new ArrayList<>();
    private final List<PendingEvent> events = new ArrayList<>();

    @BeforeEach
    public void setUp() throws SQLException {
        harness = SqliteHarness.open(folder.resolve("archived"));
        reference = SqliteHarness.open(folder.resolve("reference"));
        segments = new CountingSegmentRepository();
        archive = Archives.create(segments, BUSY, EXACT);
        store = new EventStore(new EventRepository(), new QueryResolver(new DictionaryRepository(),
                new ActorRepository()), archive);
        referenceStore = new EventStore(new EventRepository(), new QueryResolver(new DictionaryRepository(),
                new ActorRepository()), Archives.create());
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
    public void areaAndWorldPruningKeepsChatInsideTheAreaAndCombinedFilters() throws SQLException {
        Random random = new Random(23);
        events.clear();
        for (int i = 0; i < EVENTS; i++) {
            PendingEvent event = event(random, T0 + i * STEP);
            if (event.action() == Action.CHAT) {
                // Chat said where it happened: mostly among the block changes, some far from everything else.
                long position = random.nextInt(4) == 0
                        ? BlockPositions.pack(3_000 + random.nextInt(5), 64, 3_000 + random.nextInt(5))
                        : BlockPositions.pack(random.nextInt(80) - 40, 64, random.nextInt(80) - 40);
                event = new PendingEvent(event.stamp(), random.nextBoolean() ? "world" : "nether", position,
                        event.actor(), event.action(), event.subject(), event.secondary(), event.amount(),
                        event.data());
            }
            events.add(event);
        }
        archiveInTwoWaves();
        LookupQuery everything = LookupQuery.at(new Area.Everywhere());
        List<Long> flagged = pages(referenceStore, reference, everything, true).stream().filter(id -> id % 3 == 0)
                .toList();
        assertEquals(flagged.size(), referenceStore.setRolledBack(reference.shard(), flagged, true));
        assertEquals(flagged.size(), store.setRolledBack(harness.shard(), flagged, true));

        Area.Box farChat = new Area.Box("world", 2_990, 0, 2_990, 3_010, 100, 3_010);
        Area.Box near = new Area.Box("world", -30, 60, -30, 30, 70, 30);
        List<LookupQuery> queries = List.of(
                LookupQuery.at(farChat),
                query(farChat, Set.of("player2", "player3"), Set.of(Action.CHAT), Set.of()),
                LookupQuery.at(new Area.World("nether")),
                LookupQuery.at(new Area.World("world")).withRollbackState(RollbackState.ACTIVE),
                query(near, Set.of("player1", "player3"), Set.of(Action.BLOCK_BREAK, Action.CHAT), Set.of())
                        .withRollbackState(RollbackState.ROLLED_BACK),
                query(near, Set.of("player1", "player3"), Set.of(Action.BLOCK_BREAK, Action.CHAT), Set.of())
                        .withRollbackState(RollbackState.ACTIVE),
                query(new Area.Box("nether", -40, 60, -40, 40, 70, 40), Set.of("player0"), Set.of(), Set.of(
                        "minecraft:stone")).withRollbackState(RollbackState.ACTIVE),
                query(near, Set.of(), Set.of(Action.CONTAINER_DEPOSIT), Set.of("minecraft:diamond"))
                        .withTime(new TimeWindow(T0 + 4_000_000, T0 + 18_000_000)));
        for (int i = 0; i < queries.size(); i++) {
            LookupQuery query = queries.get(i);
            List<Long> expected = pages(referenceStore, reference, query, true);
            assertTrue(expected.size() > 0, "pruning query " + i + " matches something");
            assertEquals(expected, pages(store, harness, query, true), "newest-first pages of pruning query " + i);
            assertEquals(expected.reversed(), pages(store, harness, query, false),
                    "oldest-first pages of pruning query " + i);
            assertEquals(expected.size(), store.count(harness.shard(), query, ALL), "count of pruning query " + i);
            assertEquals(describe(referenceStore.find(reference.shard(), query, ALL, 0, 10_000, true)),
                    describe(store.find(harness.shard(), query, ALL, 0, 10_000, true)),
                    "every column of pruning query " + i);
        }
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
        while (referenceStore.purgeBatch(reference.shard(), bound, List.of(), 400) > 0) {
            // The same on the hot-only copy.
        }
        assertLookupsMatchReference("after purging whole and straddling segments");
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

        long indexBefore = indexBytes();
        while (store.purgeBatch(harness.shard(), bound, List.of(nether), 400) > 0) {
            // Purge until nothing older is left.
        }
        int referenceNether = worldId(referenceStore, "nether");
        while (referenceStore.purgeBatch(reference.shard(), bound, List.of(referenceNether), 400) > 0) {
            // The same on the hot-only copy.
        }
        assertLookupsMatchReference("after purging a world");
        assertTrue(indexBytes() < indexBefore, "the index shrinks: " + indexBefore + " -> " + indexBytes());
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

    @Test
    public void aBlockNeverTouchedInABusyChunkReadsNoSegmentBody() throws SQLException {
        archiveInTwoWaves();
        long untouched = BlockPositions.pack(0, 0, 0);
        assertTrue(busy(worldId("world"), untouched), "the chunk at the origin is in every segment");
        segments.takeBodyReads();

        assertEquals(List.of(), store.find(harness.shard(), LookupQuery.at(new Area.Block("world", 0, 0, 0)), ALL, 0,
                10, true));
        assertEquals(0, store.count(harness.shard(), LookupQuery.at(new Area.Block("world", 0, 0, 0)), ALL));
        assertEquals(List.of(), segments.takeBodyReads(), "no segment body is read");

        LookupQuery touched = queries().get(1);
        assertEquals(pages(referenceStore, reference, touched, true), pages(store, harness, touched, true));
        assertTrue(!segments.takeBodyReads().isEmpty(), "a block with history reads the segments holding it");
    }

    @Test
    public void aBlockTouchedOnceInABusyChunkReadsOneSegmentAndChatIsFoundWhereItWasSaid() throws SQLException {
        long chatAt = BlockPositions.pack(3, 64, 4);
        long onceAt = BlockPositions.pack(5, 71, 6);
        archiveInSegmentsOf50(41, Map.of(1_700, chatAt, 2_100, onceAt), Set.of());
        assertTrue(archive.totals(harness.shard()).segments() > 64, "more than four buckets of segments");
        assertTrue(busy(worldId("world"), onceAt) && busy(worldId("world"), chatAt));
        segments.takeBodyReads();

        LookupQuery once = LookupQuery.at(new Area.Block("world", 5, 71, 6));
        List<Long> expected = pages(referenceStore, reference, once, true);
        assertEquals(1, expected.size());
        assertEquals(expected, pages(store, harness, once, true));
        assertEquals(1, new HashSet<>(segments.takeBodyReads()).size(), "only the segment holding it is read");

        LookupQuery chat = LookupQuery.at(new Area.Block("world", 3, 64, 4));
        List<StoredEvent> said = store.find(harness.shard(), chat, ALL, 0, 10, true);
        assertEquals(1, said.size());
        assertEquals(Action.CHAT, said.getFirst().action());
        assertEquals(pages(referenceStore, reference, chat, true), List.of(said.getFirst().id()));
        assertLookupsMatchReference("segments of 50 events");
    }

    @Test
    public void aBlockTouchedInTwoDistantSegmentsOfABusyChunkReadsExactlyThoseTwo() throws SQLException {
        long twiceAt = BlockPositions.pack(-7, 66, 12);
        archiveInSegmentsOf50(43, Map.of(600, twiceAt, 3_300, twiceAt), Set.of());
        assertTrue(busy(worldId("world"), twiceAt));
        segments.takeBodyReads();

        LookupQuery twice = LookupQuery.at(new Area.Block("world", -7, 66, 12));
        List<Long> expected = pages(referenceStore, reference, twice, true);
        assertEquals(2, expected.size());
        assertEquals(expected, pages(store, harness, twice, true));
        assertEquals(expected.reversed(), pages(store, harness, twice, false));
        assertEquals(2, store.count(harness.shard(), twice, ALL));
        List<Long> read = new HashSet<>(segments.takeBodyReads()).stream().sorted().toList();
        assertEquals(2, read.size(), "only the two segments holding it are read: " + read);
        assertTrue(read.get(1) - read.get(0) > 1 << PlaceChunk.BUCKET_SHIFT, "far apart: " + read);
    }

    @Test
    public void aBlockInMoreSegmentsThanTheExactLimitPagesLikeTheReferenceThroughRewritesAndPurges()
            throws SQLException {
        long oftenAt = BlockPositions.pack(9, 63, -9);
        Map<Integer, Long> often = new LinkedHashMap<>();
        for (int i = 100; i < EVENTS; i += 150) {
            often.put(i, oftenAt);
        }
        archiveInSegmentsOf50(47, often, Set.of(250, 1_000, 2_200));
        int world = worldId("world");
        PlaceChunk row = blockRow(world, oftenAt);
        PlaceChunk.Touches touches = row.positions().get(PlaceKeys.local(oftenAt));
        assertEquals(EXACT, touches.exact().count(), "the newest ids stay exact");
        assertTrue(!touches.older().isEmpty(), "the older ones are in buckets");

        LookupQuery block = LookupQuery.at(new Area.Block("world", 9, 63, -9));
        List<Long> expected = pages(referenceStore, reference, block, true);
        assertEquals(often.size(), expected.size());
        assertEquals(expected, pages(store, harness, block, true));
        assertEquals(expected.reversed(), pages(store, harness, block, false));
        assertEquals(expected.size(), store.count(harness.shard(), block, ALL));

        // A world purge rewrites the segments that also hold the nether, and drops the exact ids it rewrote.
        long netherBound = T0 + 12_000_000;
        int nether = worldId("nether");
        while (store.purgeBatch(harness.shard(), netherBound, List.of(nether), 400) > 0) {
            // Purge until nothing older is left.
        }
        int referenceNether = worldId(referenceStore, "nether");
        while (referenceStore.purgeBatch(reference.shard(), netherBound, List.of(referenceNether), 400) > 0) {
            // The same on the hot-only copy.
        }
        assertEquals(expected, pages(store, harness, block, true), "after a world purge");
        assertEquals(expected.reversed(), pages(store, harness, block, false), "after a world purge");
        assertLookupsMatchReference("after a world purge");

        long bound = T0 + 15_000_000;
        while (store.purgeBatch(harness.shard(), bound, List.of(), 400) > 0) {
            // Purge until nothing older is left.
        }
        while (referenceStore.purgeBatch(reference.shard(), bound, List.of(), 400) > 0) {
            // The same on the hot-only copy.
        }
        List<Long> kept = pages(referenceStore, reference, block, true);
        assertTrue(!kept.isEmpty() && kept.size() < expected.size());
        assertEquals(kept, pages(store, harness, block, true), "after a purge");
        assertEquals(kept.size(), store.count(harness.shard(), block, ALL));
        assertLookupsMatchReference("after a purge");
    }

    @Test
    public void purgingOldSegmentsShrinksTheIndex() throws SQLException {
        Random random = new Random(13);
        for (int i = 0; i < EVENTS / 2; i += 3) {
            // A build site that was busy early on and then left alone.
            PendingEvent event = events.get(i);
            events.set(i, new PendingEvent(event.stamp(), "world", BlockPositions.pack(5_000 + random.nextInt(40), 64,
                    5_000 + random.nextInt(40)), event.actor(), Action.BLOCK_PLACE, "minecraft:stone", "minecraft:air",
                    0, null));
        }
        referenceStore.write(reference.shard(), referenceNode, "survival", events);
        store.write(harness.shard(), node, "survival", events);
        while (archive.compact(harness.shard(), SEGMENTS_OF_50, END + 120_000) > 0) {
            // Pack everything, 50 events a segment: five buckets.
        }
        assertTrue(busy(worldId("world"), BlockPositions.pack(5_010, 64, 5_010)), "the old site is busy");
        long indexBefore = indexBytes();
        long bound = T0 + EVENTS * STEP * 3 / 5;
        while (store.purgeBatch(harness.shard(), bound, List.of(), 400) > 0) {
            // Purge until nothing older is left.
        }
        while (referenceStore.purgeBatch(reference.shard(), bound, List.of(), 400) > 0) {
            // The same on the hot-only copy.
        }
        assertLookupsMatchReference("after purging three fifths");
        assertTrue(!busy(worldId("world"), BlockPositions.pack(5_010, 64, 5_010)), "the old site's rows are gone");
        assertTrue(indexBytes() < indexBefore, "the index shrinks: " + indexBefore + " -> " + indexBytes());
        assertEquals(List.of(), store.find(harness.shard(), LookupQuery.at(new Area.Box("world", 5_000, 0, 5_000,
                5_040, 100, 5_040)), ALL, 0, 10, true));
    }

    @Test
    public void catchingUpBuildsTheIndexThatPackingBuiltAndLookupsHoldHalfway() throws SQLException {
        archiveInTwoWaves();
        Map<String, Object> packed = indexContent();
        assertTrue(packed.keySet().stream().anyMatch(key -> key.startsWith("chunk")), "some chunks are busy");
        assertTrue(packed.values().stream().filter(PlaceRegion.class::isInstance).map(PlaceRegion.class::cast)
                .anyMatch(region -> region.chunks().keySet().stream().anyMatch(index -> !region.busy(index))),
                "some chunks are not");
        harness.database().transaction(connection -> {
            new SegmentIndexRepository().reset(connection, harness.database().sql());
            return null;
        });
        assertEquals(Map.of(), indexContent());
        assertLookupsMatchReference("nothing indexed");

        SegmentIndex.CatchUp first = archive.catchUpIndex(harness.shard(), 2);
        assertEquals(new SegmentIndex.CatchUp(2, true), first);
        assertLookupsMatchReference("half indexed");

        SegmentIndex.CatchUp rest = archive.catchUpIndex(harness.shard(), 100);
        assertEquals(new SegmentIndex.CatchUp(4, false), rest);
        assertEquals(packed, indexContent(), "catching up gives the rows packing gave");
        assertLookupsMatchReference("caught up");
        assertEquals(new SegmentIndex.CatchUp(0, false), archive.catchUpIndex(harness.shard(), 100));
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

    /** Every query, every page, newest and oldest first, and the counts, as the hot-only copy gives them. */
    private void assertLookupsMatchReference(String label) throws SQLException {
        List<LookupQuery> queries = queries();
        for (int i = 0; i < queries.size(); i++) {
            LookupQuery query = queries.get(i);
            List<Long> expected = pages(referenceStore, reference, query, true);
            assertEquals(expected, pages(store, harness, query, true), label + ": newest-first pages of query " + i);
            assertEquals(expected.reversed(), pages(store, harness, query, false),
                    label + ": oldest-first pages of query " + i);
            assertEquals(expected.size(), store.count(harness.shard(), query, ALL), label + ": count of query " + i);
        }
    }

    /**
     * Writes {@link #events} with some events moved to blocks of the world (by index; those with
     * {@code index % 7 == 6} become chat said there, the others block breaks), and every other event at
     * those blocks dropped, to both copies, and packs the archived copy in segments of 50. The late
     * events are written and packed after all others, so their segment overlaps the rest.
     */
    private void archiveInSegmentsOf50(int seed, Map<Integer, Long> moved, Set<Integer> late) throws SQLException {
        events.clear();
        Random random = new Random(seed);
        Set<Long> special = new HashSet<>(moved.values());
        List<PendingEvent> lateEvents = new ArrayList<>();
        for (int i = 0; i < EVENTS; i++) {
            PendingEvent event = event(random, T0 + i * STEP);
            Long position = moved.get(i);
            if (position != null) {
                event = i % 7 == 6
                        ? new PendingEvent(event.stamp(), "world", position, event.actor(), Action.CHAT, "", null, 0,
                                new byte[] {1, 'h', 'e', 'r', 'e'})
                        : new PendingEvent(event.stamp(), "world", position, event.actor(), Action.BLOCK_BREAK,
                                "minecraft:stone", "minecraft:air", 0, null);
            } else if (event.world().equals("world") && special.contains(event.position())) {
                continue;
            }
            (late.contains(i) ? lateEvents : events).add(event);
        }
        events.addAll(lateEvents);
        referenceStore.write(reference.shard(), referenceNode, "survival", events);
        store.write(harness.shard(), node, "survival", events.subList(0, events.size() - lateEvents.size()));
        int packed = 0;
        int pass;
        while ((pass = archive.compact(harness.shard(), SEGMENTS_OF_50, END + 120_000)) > 0) {
            packed += pass;
        }
        store.write(harness.shard(), node, "survival", lateEvents);
        // Hours later, so a short last segment is packed too.
        while ((pass = archive.compact(harness.shard(), SEGMENTS_OF_50, END + 3 * 3_600_000L)) > 0) {
            packed += pass;
        }
        assertEquals(events.size(), packed);
    }

    /** The decoded block row of a chunk. */
    private PlaceChunk blockRow(int world, long position) throws SQLException {
        long chunk = PlaceKeys.chunk(position);
        byte[] body = harness.database().read(connection -> new PlaceIndexRepository().read(connection,
                harness.database().sql(), PlaceIndexRepository.Kind.CHUNK, world, List.of(chunk)).get(chunk));
        return PlaceChunk.fromBody(world, chunk, body, EXACT);
    }

    /** Whether a chunk has a {@code block_segment} row. */
    private boolean busy(int world, long position) throws SQLException {
        long chunk = PlaceKeys.chunk(position);
        return harness.database().read(connection -> !new PlaceIndexRepository().read(connection,
                harness.database().sql(), PlaceIndexRepository.Kind.CHUNK, world, List.of(chunk)).isEmpty());
    }

    /** Every index row, decoded, by table, world, key and newest segment. */
    private Map<String, Object> indexContent() throws SQLException {
        return harness.database().read(connection -> {
            Map<String, Object> out = new LinkedHashMap<>();
            try (Statement statement = connection.createStatement()) {
                try (ResultSet rows = statement.executeQuery("SELECT world, region, newest, body FROM "
                        + harness.database().tables().regionSegment() + " ORDER BY world, region")) {
                    while (rows.next()) {
                        out.put("region " + rows.getInt(1) + " " + rows.getLong(2) + " " + rows.getLong(3),
                                PlaceRegion.fromBody(rows.getInt(1), rows.getLong(2), rows.getBytes(4)));
                    }
                }
                try (ResultSet rows = statement.executeQuery("SELECT world, chunk, newest, body FROM "
                        + harness.database().tables().blockSegment() + " ORDER BY world, chunk")) {
                    while (rows.next()) {
                        out.put("chunk " + rows.getInt(1) + " " + rows.getLong(2) + " " + rows.getLong(3),
                                PlaceChunk.fromBody(rows.getInt(1), rows.getLong(2), rows.getBytes(4), EXACT));
                    }
                }
            }
            return out;
        });
    }

    /** The bytes of every index row. */
    private long indexBytes() throws SQLException {
        return harness.database().read(connection -> {
            try (Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery("SELECT (SELECT COALESCE(SUM(LENGTH(body)), 0) FROM "
                            + harness.database().tables().regionSegment() + ") + (SELECT COALESCE(SUM(LENGTH(body)), 0) "
                            + "FROM " + harness.database().tables().blockSegment() + ")")) {
                rows.next();
                return rows.getLong(1);
            }
        });
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
        PendingEvent scattered = events.stream().filter(event -> BlockPositions.x(event.position()) >= 1_000)
                .skip(5).findFirst().orElseThrow();
        queries.add(LookupQuery.at(new Area.Block(scattered.world(), BlockPositions.x(scattered.position()),
                BlockPositions.y(scattered.position()), BlockPositions.z(scattered.position()))));
        queries.add(LookupQuery.at(new Area.Box("world", 1_000, 0, 0, 1_250, 100, 250)));
        queries.add(LookupQuery.at(new Area.Box("nether", 1_000, 0, -40, 1_100, 255, 100)));
        queries.add(query(new Area.Box("world", -40, 0, -40, 40, 255, 40), Set.of("player3"), Set.of(), Set.of()));
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
        long position = random.nextInt(20) == 0
                ? BlockPositions.pack(1_000 + random.nextInt(600), 60 + random.nextInt(10), random.nextInt(600))
                : BlockPositions.pack(random.nextInt(80) - 40, 60 + random.nextInt(10), random.nextInt(80) - 40);
        return new PendingEvent(EventIds.stamp(millis, sequence), random.nextBoolean() ? "world" : "nether", position,
                actor,
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
        return worldId(store, name);
    }

    private int worldId(EventStore on, String name) throws SQLException {
        Map<Integer, WorldKey> worlds = on.worlds(on == store ? harness.shard() : reference.shard(),
                List.of("survival"));
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
