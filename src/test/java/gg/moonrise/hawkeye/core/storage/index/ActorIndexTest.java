package gg.moonrise.hawkeye.core.storage.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.config.file.ArchiveSettings;
import gg.moonrise.hawkeye.core.repository.archive.CountingSegmentRepository;
import gg.moonrise.hawkeye.core.repository.archive.SegmentRepository;
import gg.moonrise.hawkeye.core.repository.dictionary.ActorRepository;
import gg.moonrise.hawkeye.core.repository.dictionary.DictionaryRepository;
import gg.moonrise.hawkeye.core.repository.event.EventFilter;
import gg.moonrise.hawkeye.core.repository.event.EventRepository;
import gg.moonrise.hawkeye.core.repository.index.ActorIndexRepository;
import gg.moonrise.hawkeye.core.repository.index.SegmentIndexRepository;
import gg.moonrise.hawkeye.core.sql.SqliteHarness;
import gg.moonrise.hawkeye.core.storage.archive.Archive;
import gg.moonrise.hawkeye.core.storage.archive.Archives;
import gg.moonrise.hawkeye.core.storage.archive.SegmentCache;
import gg.moonrise.hawkeye.core.storage.event.EventStore;
import gg.moonrise.hawkeye.core.storage.event.QueryResolver;
import gg.moonrise.hawkeye.core.storage.index.place.IdRuns;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The actor part of the segment index: a lookup by a few players reads only the segments that hold
 * their rows, and returns what a hot-only reference copy of the same events returns, through purges,
 * rewrites and a rebuild. Events come from a pool of {@link #POOL} players in segments of 50; a rare
 * player is in two of them, an early one only in the first few.
 */
public class ActorIndexTest {

    private static final long T0 = EventIds.EPOCH_MILLIS + 86_400_000L * 400;
    private static final long STEP = 7_000;
    private static final int EVENTS = 4_000;
    private static final long END = T0 + EVENTS * STEP;
    private static final int POOL = 70;
    private static final ShardScope ALL = new ShardScope.All();
    private static final ArchiveSettings SEGMENTS_OF_50 = new ArchiveSettings().withPacking(50, 60, 1);

    @TempDir
    private Path folder;

    private SqliteHarness harness;
    private SqliteHarness reference;
    private EventStore store;
    private EventStore referenceStore;
    private Archive archive;
    private SegmentIndex index;
    private CountingSegmentRepository segments;
    private ServerNode node;
    private ServerNode referenceNode;
    private final List<Actor> pool = new ArrayList<>();
    private final Actor rare = Actor.player(new UUID(1, 1), "rare");
    private final Actor early = Actor.player(new UUID(1, 2), "early");
    private final Actor newcomer = Actor.player(new UUID(1, 3), "newcomer");
    private final List<PendingEvent> events = new ArrayList<>();

    @BeforeEach
    public void setUp() throws SQLException {
        harness = SqliteHarness.open(folder.resolve("archived"));
        reference = SqliteHarness.open(folder.resolve("reference"));
        segments = new CountingSegmentRepository();
        SegmentCache cache = new SegmentCache();
        index = Archives.index(segments, cache, 2, 4);
        archive = new Archive(new EventRepository(), segments, cache, index);
        store = new EventStore(new EventRepository(), resolver(), archive);
        referenceStore = new EventStore(new EventRepository(), resolver(), Archives.create());
        node = harness.register("survival", "survival");
        referenceNode = reference.register("survival", "survival");
        for (int i = 0; i < POOL; i++) {
            pool.add(Actor.player(new UUID(0, i + 1), "player" + i));
        }
        Random random = new Random(17);
        for (int i = 0; i < EVENTS; i++) {
            Actor actor = i == 700 || i == 3_100 ? rare : i == 10 || i == 60 || i == 110 ? early
                    : pool.get(random.nextInt(i % 3 == 0 ? 6 : POOL));
            events.add(event(random, T0 + i * STEP, actor));
        }
        referenceStore.write(reference.shard(), referenceNode, "survival", events);
        store.write(harness.shard(), node, "survival", events);
        int packed = 0;
        int pass;
        while ((pass = archive.compact(harness.shard(), SEGMENTS_OF_50, END + 3 * 3_600_000L)) > 0) {
            packed += pass;
        }
        assertEquals(EVENTS, packed);
        // Hot events of a player no segment holds, and of a pool player.
        List<PendingEvent> hot = List.of(event(random, END + 10_000, newcomer), event(random, END + 20_000, newcomer),
                event(random, END + 30_000, pool.get(3)));
        referenceStore.write(reference.shard(), referenceNode, "survival", hot);
        store.write(harness.shard(), node, "survival", hot);
        segments.takeBodyReads();
    }

    @AfterEach
    public void tearDown() {
        harness.close();
        reference.close();
    }

    @Test
    public void aPlayerInTwoOfManySegmentsReadsOnlyThose() throws SQLException {
        assertEquals(80, archive.totals(harness.shard()).segments());
        LookupQuery query = byActors(Set.of("rare"));
        List<Long> expected = pages(referenceStore, reference, query, true);
        assertEquals(2, expected.size());
        assertEquals(expected, pages(store, harness, query, true));
        assertEquals(expected.reversed(), pages(store, harness, query, false));
        assertEquals(2, store.count(harness.shard(), query, ALL));
        assertEquals(2, new HashSet<>(segments.takeBodyReads()).size(), "only the two segments holding it are read");
        assertEquals(2, plan(query).orElseThrow().count());
    }

    @Test
    public void thePageOfAPlayerInEverySegmentWalksTheIndexOnceAndReadsTheNewestSegments() throws SQLException {
        LookupQuery query = byActors(Set.of("player3"));
        assertTrue(SegmentRepository.scattered(plan(query).orElseThrow()), "the plan names more segments than are sought");
        List<Long> expected = referenceStore.find(reference.shard(), query, ALL, 0, 5, true).stream()
                .map(StoredEvent::id).toList();
        segments.takeWalks();
        segments.takeBodyReads();

        assertEquals(expected, store.find(harness.shard(), query, ALL, 0, 5, true).stream().map(StoredEvent::id).toList());
        assertEquals(List.of(Archive.FIRST_WALK), segments.takeWalks(), "one statement names enough segments");
        assertTrue(new HashSet<>(segments.takeBodyReads()).size() <= 3, "only the newest segments are read");

        List<Long> all = pages(referenceStore, reference, query, true);
        assertEquals(all, pages(store, harness, query, true));
        assertEquals(all.reversed(), pages(store, harness, query, false));
        assertEquals(all.size(), store.count(harness.shard(), query, ALL));
        segments.takeWalks();
        store.count(harness.shard(), query, ALL);
        assertEquals(List.of(Archive.FIRST_WALK, 4 * Archive.FIRST_WALK), segments.takeWalks(),
                "a count walks every candidate, four times as many a statement");
    }

    @Test
    public void aPlayerWithoutARowReadsNoSegment() throws SQLException {
        LookupQuery query = byActors(Set.of("newcomer"));
        List<Long> expected = pages(referenceStore, reference, query, true);
        assertEquals(2, expected.size(), "only hot events");
        assertEquals(expected, pages(store, harness, query, true));
        assertEquals(2, store.count(harness.shard(), query, ALL));
        assertEquals(List.of(), segments.takeBodyReads());
        assertEquals(Optional.of(new IdRuns()), plan(query));
    }

    @Test
    public void aPlayerAndAnAreaReadNoMoreThanEitherAlone() throws SQLException {
        Area.Box box = new Area.Box("world", -10, 60, -10, 10, 70, 10);
        LookupQuery actor = byActors(Set.of("player4"));
        LookupQuery area = LookupQuery.at(box);
        LookupQuery both = query(box, Set.of("player4"), Set.of());
        for (LookupQuery query : List.of(actor, area, both)) {
            List<Long> expected = pages(referenceStore, reference, query, true);
            assertTrue(!expected.isEmpty());
            assertEquals(expected, pages(store, harness, query, true));
            assertEquals(expected.size(), store.count(harness.shard(), query, ALL));
        }
        long byActor = plan(actor).orElseThrow().count();
        long byArea = plan(area).orElseThrow().count();
        long byBoth = plan(both).orElseThrow().count();
        assertTrue(byBoth <= Math.min(byActor, byArea) && byBoth < Math.max(byActor, byArea),
                byActor + " " + byArea + " " + byBoth);
        assertEquals(plan(actor).orElseThrow().intersect(plan(area).orElseThrow()), plan(both).orElseThrow());

        segments.takeBodyReads();
        store.count(harness.shard(), both, ALL);
        int bothReads = new HashSet<>(segments.takeBodyReads()).size();
        store.count(harness.shard(), actor, ALL);
        int actorReads = new HashSet<>(segments.takeBodyReads()).size();
        store.count(harness.shard(), area, ALL);
        int areaReads = new HashSet<>(segments.takeBodyReads()).size();
        assertTrue(bothReads <= Math.min(actorReads, areaReads), bothReads + " " + actorReads + " " + areaReads);
    }

    @Test
    public void excludedPlayersAndLongListsUseTheKeysetPathAndStayCorrect() throws SQLException {
        Set<String> sixtyFive = IntStream.range(0, 64).mapToObj(i -> "player" + i).collect(Collectors.toSet());
        Set<String> sixtyFour = new HashSet<>(sixtyFive);
        sixtyFive.add("rare");
        LookupQuery excluded = query(new Area.Everywhere(), Set.of(), Set.of("player0", "player1", "rare"));
        LookupQuery tooMany = byActors(sixtyFive);
        LookupQuery listedAndExcluded = query(new Area.Everywhere(), Set.of("rare", "player2"), Set.of("player2"));
        assertEquals(Optional.empty(), plan(excluded), "excluded players never narrow");
        assertEquals(Optional.empty(), plan(tooMany), "more than " + ActorIndex.MAX_ACTORS + " players");
        assertTrue(plan(byActors(sixtyFour)).isPresent(), ActorIndex.MAX_ACTORS + " players are narrowed");
        assertTrue(plan(listedAndExcluded).isPresent(), "the listed players narrow");
        for (LookupQuery query : List.of(excluded, tooMany, byActors(sixtyFour), listedAndExcluded)) {
            List<Long> expected = pages(referenceStore, reference, query, true);
            assertTrue(!expected.isEmpty());
            assertEquals(expected, pages(store, harness, query, true));
            assertEquals(expected.reversed(), pages(store, harness, query, false));
            assertEquals(expected.size(), store.count(harness.shard(), query, ALL));
        }
    }

    @Test
    public void aPurgeTrimsTheRowsOfPlayersLeftWithNoSegment() throws SQLException {
        int earlyId = actorId("early");
        assertTrue(actorRows().containsKey(earlyId));
        long bound = T0 + 2_000 * STEP;
        purge(bound, List.of());
        assertTrue(!actorRows().containsKey(earlyId), "the early player's row is gone");
        long oldest = oldestSegment();
        assertTrue(actorRows().values().stream().allMatch(ids -> ids.max() >= oldest),
                "no row names only deleted segments");
        assertActorLookupsMatchReference("after a purge");
        assertEquals(List.of(), pages(store, harness, byActors(Set.of("early")), true));
    }

    @Test
    public void aWorldPurgeThatRewritesSegmentsKeepsPlayerLookupsCorrect() throws SQLException {
        Map<Integer, IdRuns> before = actorRows();
        purge(T0 + 3_500 * STEP, List.of("nether"));
        assertTrue(!before.equals(actorRows()), "rewritten segments got new ids");
        int rareId = actorId("rare");
        long left = pages(referenceStore, reference, byActors(Set.of("rare")), true).size();
        IdRuns rareRow = actorRows().getOrDefault(rareId, new IdRuns());
        assertEquals(left, rareRow.count(), "the rare player's row names the segments still holding its events");
        assertActorLookupsMatchReference("after a world purge");
    }

    @Test
    public void catchingUpBuildsTheActorRowsPackingBuiltAndLookupsHoldHalfway() throws SQLException {
        Map<Integer, IdRuns> packed = actorRows();
        assertTrue(packed.size() > ActorIndex.MAX_ACTORS);
        harness.database().transaction(connection -> {
            new SegmentIndexRepository().reset(connection, harness.database().sql());
            return null;
        });
        assertEquals(Map.of(), actorRows());
        assertActorLookupsMatchReference("nothing indexed");

        assertEquals(new SegmentIndex.CatchUp(30, true), archive.catchUpIndex(harness.shard(), 30));
        assertActorLookupsMatchReference("half indexed");
        assertEquals(new SegmentIndex.CatchUp(50, false), archive.catchUpIndex(harness.shard(), 100));
        assertEquals(packed, actorRows(), "catching up gives the rows packing gave");
        assertActorLookupsMatchReference("caught up");
    }

    private void assertActorLookupsMatchReference(String label) throws SQLException {
        List<LookupQuery> queries = List.of(byActors(Set.of("rare")), byActors(Set.of("early")),
                byActors(Set.of("player3")), byActors(Set.of("player40", "rare", "newcomer")),
                query(new Area.Box("world", -40, 0, -40, 40, 255, 40), Set.of("player1"), Set.of()),
                query(new Area.World("nether"), Set.of("player5", "rare"), Set.of()));
        for (int i = 0; i < queries.size(); i++) {
            LookupQuery query = queries.get(i);
            List<Long> expected = pages(referenceStore, reference, query, true);
            assertEquals(expected, pages(store, harness, query, true), label + ": newest-first pages of query " + i);
            assertEquals(expected.reversed(), pages(store, harness, query, false),
                    label + ": oldest-first pages of query " + i);
            assertEquals(expected.size(), store.count(harness.shard(), query, ALL), label + ": count of query " + i);
        }
    }

    private void purge(long bound, List<String> worldNames) throws SQLException {
        List<Integer> worlds = new ArrayList<>();
        List<Integer> referenceWorlds = new ArrayList<>();
        for (String name : worldNames) {
            worlds.add(worldId(store, harness, name));
            referenceWorlds.add(worldId(referenceStore, reference, name));
        }
        while (store.purgeBatch(harness.shard(), bound, worlds, 400) > 0) {
            // Purge until nothing older is left.
        }
        while (referenceStore.purgeBatch(reference.shard(), bound, referenceWorlds, 400) > 0) {
            // The same on the hot-only copy.
        }
    }

    private Optional<IdRuns> plan(LookupQuery query) throws SQLException {
        return harness.database().read(connection -> {
            Optional<EventFilter> filter = resolver().resolve(connection, harness.shard(), query, ALL);
            return index.plan(connection, harness.shard(), filter.orElseThrow());
        });
    }

    private int actorId(String name) throws SQLException {
        return harness.database().read(connection -> resolver().resolve(connection, harness.shard(),
                byActors(Set.of(name)), ALL)).orElseThrow().actors().iterator().next();
    }

    /** Every actor row, decoded, by actor id. */
    private Map<Integer, IdRuns> actorRows() throws SQLException {
        return harness.database().read(connection -> {
            Map<Integer, IdRuns> out = new TreeMap<>();
            try (Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery("SELECT actor, newest, body FROM "
                            + harness.database().tables().actorSegment())) {
                while (rows.next()) {
                    IdRuns ids = IdRuns.fromBytes(rows.getBytes(3));
                    assertEquals(ids.max(), rows.getLong(2), "newest is the row's largest id");
                    out.put(rows.getInt(1), ids);
                }
            }
            Map<Integer, byte[]> read = new ActorIndexRepository().read(connection, harness.database().sql(),
                    out.keySet());
            assertEquals(out.keySet(), read.keySet(), "the repository reads every row");
            return out;
        });
    }

    private long oldestSegment() throws SQLException {
        return harness.database().read(connection -> segments.oldestSegmentId(connection, harness.database().sql()));
    }

    private static int worldId(EventStore on, SqliteHarness harness, String name) throws SQLException {
        Map<Integer, WorldKey> worlds = on.worlds(harness.shard(), List.of("survival"));
        return worlds.entrySet().stream().filter(entry -> entry.getValue().name().equals(name)).findFirst()
                .orElseThrow().getKey();
    }

    private static QueryResolver resolver() {
        return new QueryResolver(new DictionaryRepository(), new ActorRepository());
    }

    private static LookupQuery byActors(Set<String> names) {
        return query(new Area.Everywhere(), names, Set.of());
    }

    private static LookupQuery query(Area area, Set<String> actorNames, Set<String> excluded) {
        return new LookupQuery(new ServerScope.Local(), area, TimeWindow.ALL, actorNames, excluded, Set.of(), Set.of(),
                Set.of(), RollbackState.ANY);
    }

    private static PendingEvent event(Random random, long millis, Actor actor) {
        int roll = random.nextInt(10);
        Action action = roll == 0 ? Action.CHAT : random.nextBoolean() ? Action.BLOCK_PLACE : Action.BLOCK_BREAK;
        String world = random.nextBoolean() ? "world" : "nether";
        if (action == Action.CHAT) {
            return new PendingEvent(EventIds.stamp(millis, 0), world, 0, actor, action, "", null, 0,
                    new byte[] {1, 'h', 'i', (byte) random.nextInt(100)});
        }
        long position = BlockPositions.pack(random.nextInt(80) - 40, 60 + random.nextInt(10), random.nextInt(80) - 40);
        return new PendingEvent(EventIds.stamp(millis, 0), world, position, actor, action,
                random.nextBoolean() ? "minecraft:stone" : "minecraft:oak_log[axis=y]", "minecraft:air", 0, null);
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
}
