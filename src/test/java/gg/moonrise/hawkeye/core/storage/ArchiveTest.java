package gg.moonrise.hawkeye.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.core.repository.ActorRepository;
import gg.moonrise.hawkeye.core.repository.DictionaryRepository;
import gg.moonrise.hawkeye.core.repository.EventRepository;
import gg.moonrise.hawkeye.core.repository.SegmentRepository;
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
import gg.moonrise.hawkeye.util.BlockPositions;
import gg.moonrise.hawkeye.util.EventIds;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Archiving moves events into segments without changing what any read returns. */
public class ArchiveTest {

    private static final long T0 = EventIds.EPOCH_MILLIS + 86_400_000L * 400;
    private static final ShardScope ALL = new ShardScope.All();

    @TempDir
    private Path folder;

    private SqliteHarness harness;
    private EventStore store;
    private Archive archive;
    private ServerNode node;
    private final List<Actor> actors = new ArrayList<>();

    @BeforeEach
    public void setUp() throws SQLException {
        harness = SqliteHarness.open(folder);
        archive = new Archive(new EventRepository(), new SegmentRepository());
        store = new EventStore(new EventRepository(), new QueryResolver(new DictionaryRepository(),
                new ActorRepository()), archive);
        node = harness.register("survival", "survival");
        for (int i = 0; i < 6; i++) {
            actors.add(Actor.player(new UUID(0, i + 1), "player" + i));
        }
        Random random = new Random(3);
        List<PendingEvent> events = new ArrayList<>();
        for (int i = 0; i < 4_000; i++) {
            Action action = random.nextInt(5) == 0 ? Action.CONTAINER_DEPOSIT : random.nextBoolean()
                    ? Action.BLOCK_PLACE : Action.BLOCK_BREAK;
            String subject = action == Action.CONTAINER_DEPOSIT ? "minecraft:diamond"
                    : random.nextBoolean() ? "minecraft:stone" : "minecraft:oak_log[axis=y]";
            events.add(new PendingEvent(EventIds.stamp(T0 + i * 7_000L, 0), random.nextBoolean() ? "world" : "nether",
                    BlockPositions.pack(random.nextInt(600) - 300, random.nextInt(120), random.nextInt(600) - 300),
                    actors.get(random.nextInt(actors.size())), action, subject,
                    action == Action.CONTAINER_DEPOSIT ? null : "minecraft:air",
                    action == Action.CONTAINER_DEPOSIT ? 1 + random.nextInt(64) : 0, null));
        }
        store.write(harness.shard(), node, "survival", events);
    }

    @AfterEach
    public void tearDown() {
        harness.close();
    }

    @Test
    public void readsAreIdenticalBeforeAndAfterArchiving() throws SQLException {
        List<LookupQuery> queries = queries();
        List<List<Long>> before = new ArrayList<>();
        List<Long> countsBefore = new ArrayList<>();
        for (LookupQuery query : queries) {
            before.add(pages(query, true));
            countsBefore.add(store.count(harness.shard(), query, ALL));
        }
        List<Long> oldestFirst = pages(queries.getFirst(), false);

        long cutoff = T0 + 4_000 * 7_000L / 2;
        int moved = 0;
        int pass;
        while ((pass = archive.compact(harness.shard(), cutoff, 700)) > 0) {
            moved += pass;
        }
        assertTrue(moved > 1_500, "archived " + moved);
        assertEquals(moved, archive.totals(harness.shard())[0]);

        for (int i = 0; i < queries.size(); i++) {
            assertEquals(before.get(i), pages(queries.get(i), true), "newest-first pages of query " + i);
            assertEquals(countsBefore.get(i), store.count(harness.shard(), queries.get(i), ALL), "count of query " + i);
        }
        assertEquals(oldestFirst, pages(queries.getFirst(), false));
    }

    @Test
    public void rollbackFlagsWorkInBothTiers() throws SQLException {
        LookupQuery query = queries().get(1);
        List<StoredEvent> all = store.find(harness.shard(), query, ALL, 0, 10_000, false);
        archive.compact(harness.shard(), T0 + 4_000 * 7_000L / 2, 100_000);
        List<Long> ids = all.stream().map(StoredEvent::id).toList();
        assertEquals(ids.size(), store.setRolledBack(harness.shard(), ids, true));
        assertEquals(ids.size(), store.count(harness.shard(), query.withRollbackState(RollbackState.ROLLED_BACK), ALL));
        store.setRolledBack(harness.shard(), ids, false);
        assertEquals(0, store.count(harness.shard(), query.withRollbackState(RollbackState.ROLLED_BACK), ALL));
    }

    @Test
    public void purgeRemovesOldEventsFromBothTiers() throws SQLException {
        archive.compact(harness.shard(), T0 + 4_000 * 7_000L / 2, 100_000);
        long purgeBefore = T0 + 4_000 * 7_000L * 3 / 4;
        LookupQuery everything = LookupQuery.at(new Area.Everywhere());
        long expected = store.count(harness.shard(), everything.withTime(new TimeWindow(purgeBefore, Long.MAX_VALUE)),
                ALL);
        while (store.purgeBatch(harness.shard(), purgeBefore, List.of(), 500) > 0) {
            // Keep purging until nothing is left.
        }
        assertEquals(expected, store.count(harness.shard(), everything, ALL));
    }

    private List<LookupQuery> queries() {
        List<LookupQuery> queries = new ArrayList<>();
        queries.add(LookupQuery.at(new Area.Everywhere()));
        queries.add(query(new Area.Box("world", -50, 10, -80, 70, 90, 40), Set.of(), Set.of(), Set.of()));
        queries.add(query(new Area.Everywhere(), Set.of("player2"), Set.of(), Set.of()));
        queries.add(query(new Area.World("nether"), Set.of(), Set.of(Action.CONTAINER_DEPOSIT), Set.of()));
        queries.add(query(new Area.Everywhere(), Set.of("player1", "player4"), Set.of(), Set.of("minecraft:oak_log")));
        queries.add(query(new Area.Box("nether", -300, -64, -300, 300, 320, 300), Set.of("player5"),
                Set.of(Action.BLOCK_BREAK), Set.of()).withTime(new TimeWindow(T0 + 3_000_000, T0 + 20_000_000)));
        return queries;
    }

    private static LookupQuery query(Area area, Set<String> actorNames, Set<Action> actions, Set<String> types) {
        return new LookupQuery(new ServerScope.Local(), area, TimeWindow.ALL, actorNames, Set.of(), actions, types,
                Set.of(), RollbackState.ANY);
    }

    /** Every id the query returns, read page by page with a keyset cursor. */
    private List<Long> pages(LookupQuery query, boolean newestFirst) throws SQLException {
        List<Long> ids = new ArrayList<>();
        long cursor = 0;
        while (true) {
            List<StoredEvent> page = store.find(harness.shard(), query, ALL, cursor, 37, newestFirst);
            page.forEach(event -> ids.add(event.id()));
            if (page.size() < 37) {
                return ids;
            }
            cursor = page.getLast().id();
        }
    }
}
