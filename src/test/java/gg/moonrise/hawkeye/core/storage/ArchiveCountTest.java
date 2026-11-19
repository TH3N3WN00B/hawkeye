package gg.moonrise.hawkeye.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.config.file.ArchiveSettings;
import gg.moonrise.hawkeye.core.repository.ActorRepository;
import gg.moonrise.hawkeye.core.repository.DictionaryRepository;
import gg.moonrise.hawkeye.core.repository.EventRepository;
import gg.moonrise.hawkeye.core.sql.SqliteHarness;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.hawkeye.entity.event.PendingEvent;
import gg.moonrise.hawkeye.entity.query.Area;
import gg.moonrise.hawkeye.entity.query.EventCount;
import gg.moonrise.hawkeye.entity.query.LookupQuery;
import gg.moonrise.hawkeye.entity.query.ShardScope;
import gg.moonrise.hawkeye.entity.server.ServerNode;
import gg.moonrise.hawkeye.util.BlockPositions;
import gg.moonrise.hawkeye.util.EventIds;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A count with a segment budget: exact while the budget covers every candidate segment, a lower bound
 * read from the newest segments when it does not, and never more segment bodies read than the budget.
 * 1,000 events in a line of blocks are packed 50 to a segment (20 segments, oldest first); a lone block
 * far away is touched in three of them.
 */
public class ArchiveCountTest {

    private static final long T0 = EventIds.EPOCH_MILLIS + 86_400_000L * 400;
    private static final long STEP = 7_000;
    private static final int EVENTS = 1_000;
    private static final int PER_SEGMENT = 50;
    private static final int SEGMENTS = EVENTS / PER_SEGMENT;
    private static final ShardScope ALL = new ShardScope.All();
    private static final LookupQuery EVERYWHERE = LookupQuery.at(new Area.Everywhere());
    private static final LookupQuery LONE = LookupQuery.at(new Area.Block("world", 5_000, 64, 5_000));
    private static final Set<Integer> LONE_AT = Set.of(10, 500, 990);

    @TempDir
    private Path folder;

    private SqliteHarness harness;
    private CountingSegmentRepository segments;
    private EventStore store;
    private ServerNode node;

    @BeforeEach
    public void setUp() throws SQLException {
        harness = SqliteHarness.open(folder.resolve("archived"));
        segments = new CountingSegmentRepository();
        store = new EventStore(new EventRepository(), new QueryResolver(new DictionaryRepository(),
                new ActorRepository()), Archives.create(segments, PlaceIndex.BUSY_SEGMENTS));
        node = harness.register("survival", "survival");
        Actor actor = Actor.player(new UUID(0, 1), "player");
        List<PendingEvent> events = new ArrayList<>();
        for (int i = 0; i < EVENTS; i++) {
            long position = LONE_AT.contains(i) ? BlockPositions.pack(5_000, 64, 5_000)
                    : BlockPositions.pack(i % 40, 64, 0);
            events.add(new PendingEvent(EventIds.stamp(T0 + i * STEP, 0), "world", position, actor,
                    Action.BLOCK_BREAK, "minecraft:stone", "minecraft:air", 0, null));
        }
        store.write(harness.shard(), node, "survival", events);
        Archive archive = Archives.create(new CountingSegmentRepository(), PlaceIndex.BUSY_SEGMENTS);
        ArchiveSettings settings = new ArchiveSettings().withPacking(PER_SEGMENT, 60, 1);
        while (archive.compact(harness.shard(), settings, T0 + EVENTS * STEP + 120_000) > 0) {
            // Pack everything.
        }
        assertEquals(SEGMENTS, archive.totals(harness.shard()).segments());
        segments.takeBodyReads();
    }

    @AfterEach
    public void tearDown() {
        harness.close();
    }

    @Test
    public void aCountWithinTheBudgetIsExactAndEqualsTheUnboundedOne() throws SQLException {
        long unbounded = store.count(harness.shard(), EVERYWHERE, ALL);
        assertEquals(EVENTS, unbounded);
        segments.takeBodyReads();

        assertEquals(EventCount.of(unbounded), store.count(harness.shard(), EVERYWHERE, ALL, SEGMENTS));
        assertEquals(EventCount.of(unbounded), store.count(harness.shard(), EVERYWHERE, ALL, 64));
        assertEquals(EventCount.of(3), store.count(harness.shard(), LONE, ALL, 3));
    }

    @Test
    public void beyondTheBudgetTheNewestSegmentsGiveALowerBound() throws SQLException {
        EventCount count = store.count(harness.shard(), EVERYWHERE, ALL, 5);

        assertFalse(count.exact());
        assertEquals(EventCount.atLeast(5L * PER_SEGMENT), count, "the five newest segments' events");
        Set<Long> read = new HashSet<>(segments.takeBodyReads());
        assertEquals(5, read.size(), "no more segment bodies than the budget: " + read);
        Set<Long> newest = new HashSet<>(newestSegments(5));
        assertEquals(newest, read, "the newest segments are the ones counted");
    }

    @Test
    public void segmentsWhoseHeadersRuleThemOutCostNoBudget() throws SQLException {
        assertEquals(EventCount.of(3), store.count(harness.shard(), LONE, ALL, 3));
        assertEquals(3, new HashSet<>(segments.takeBodyReads()).size());

        assertEquals(EventCount.atLeast(2), store.count(harness.shard(), LONE, ALL, 2));
        assertTrue(new HashSet<>(segments.takeBodyReads()).size() <= 2);
        assertEquals(EventCount.atLeast(0), store.count(harness.shard(), LONE, ALL, 0));
        assertEquals(List.of(), segments.takeBodyReads());
    }

    @Test
    public void hotEventsAreAlwaysCountedExactly() throws SQLException {
        Actor actor = Actor.player(new UUID(0, 2), "late");
        List<PendingEvent> hot = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            hot.add(new PendingEvent(EventIds.stamp(T0 + (EVENTS + i) * STEP, 0), "world",
                    BlockPositions.pack(i, 64, 0), actor, Action.BLOCK_PLACE, "minecraft:stone", "minecraft:air", 0,
                    null));
        }
        store.write(harness.shard(), node, "survival", hot);

        assertEquals(EventCount.atLeast(30 + 2L * PER_SEGMENT), store.count(harness.shard(), EVERYWHERE, ALL, 2));
        assertEquals(EventCount.of(EVENTS + 30), store.count(harness.shard(), EVERYWHERE, ALL, SEGMENTS));
    }

    /** The ids of the {@code n} newest segments: events are packed oldest first, so the largest ids. */
    private List<Long> newestSegments(int n) throws SQLException {
        return harness.database().read(connection -> {
            List<Long> ids = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery("SELECT id FROM " + harness.database().tables().segment()
                            + " ORDER BY last_id DESC LIMIT " + n)) {
                while (rows.next()) {
                    ids.add(rows.getLong(1));
                }
            }
            return ids;
        });
    }
}
