package gg.moonrise.hawkeye.core.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.core.repository.place.IdRuns;
import gg.moonrise.hawkeye.core.sql.Sql;
import gg.moonrise.hawkeye.core.sql.SqliteHarness;
import gg.moonrise.hawkeye.entity.query.RollbackState;
import gg.moonrise.hawkeye.util.BlockPositions;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The query shapes of docs/architecture.md hold on SQLite: each lookup uses the index it is built for. */
public class EventSqlPlanTest {

    @TempDir
    private Path folder;

    private SqliteHarness harness;

    @BeforeEach
    public void setUp() throws SQLException {
        harness = SqliteHarness.open(folder);
    }

    @AfterEach
    public void tearDown() {
        harness.close();
    }

    @Test
    public void anAreaIsIndexRangeScansOnWorldAndPosition() throws SQLException {
        List<EventFilter.WorldRange> ranges = new ArrayList<>();
        for (BlockPositions.Range range : BlockPositions.blockRanges(-20, -20, 20, 20)) {
            ranges.add(new EventFilter.WorldRange(1, range.from(), range.to(), 3, 12));
        }
        String plan = plan(filter(Set.of(), ranges, new EventFilter.Bounds(0, 100, -20, 20), Set.of()));
        assertTrue(plan.contains("USING INDEX hawkeye_event_pos (world=? AND pos>? AND pos<?)"), plan);
    }

    @Test
    public void aSingleBlockIsOneIndexSeek() throws SQLException {
        long position = BlockPositions.pack(1, 2, 3);
        String plan = plan(filter(Set.of(), List.of(EventFilter.WorldRange.of(1, position, position)),
                EventFilter.Bounds.NONE, Set.of()));
        assertTrue(plan.contains("USING INDEX hawkeye_event_pos (world=? AND pos=?"), plan);
    }

    @Test
    public void wholeWorldsScanThePrimaryKeyNewestFirst() throws SQLException {
        String plan = plan(filter(Set.of(1, 2), List.of(), EventFilter.Bounds.NONE, Set.of()));
        assertTrue(plan.contains("USING INTEGER PRIMARY KEY"), plan);
        assertTrue(!plan.contains("hawkeye_event_pos"), plan);
    }

    @Test
    public void anActorUsesTheActorIndex() throws SQLException {
        String plan = plan(filter(Set.of(), List.of(), EventFilter.Bounds.NONE, Set.of(7, 8)));
        assertTrue(plan.contains("hawkeye_event_actor"), plan);
    }

    @Test
    public void archiveCandidatesWalkTheLastIdIndexAndSeekTheirFlags() throws SQLException {
        BoundSql query = SegmentRepository.candidateQuery(harness.database().sql(), EventFilter.everything(5, 9_000),
                0, null, 32, true);
        String plan = explain(query);
        assertTrue(plan.contains("USING INDEX hawkeye_segment_last (last_id>?)"), plan);
        assertTrue(plan.contains("USING INTEGER PRIMARY KEY (rowid=?)"), plan);
        assertTrue(!plan.contains("TEMP B-TREE"), "no sort: " + plan);
    }

    @Test
    public void aLaterPageOfArchiveCandidatesSeeksToWhereThePreviousOneEnded() throws SQLException {
        SegmentRow previous = new SegmentRow(40, 1_000, 5_000, 10, new byte[0], new int[0]);
        Sql sql = harness.database().sql();

        String newestFirst = explain(SegmentRepository.candidateQuery(sql, EventFilter.everything(5, 9_000), 0,
                previous, 32, true));
        assertTrue(newestFirst.contains("USING INDEX hawkeye_segment_last (last_id>? AND last_id<?)"), newestFirst);
        assertTrue(!newestFirst.contains("TEMP B-TREE"), "no sort: " + newestFirst);

        String oldestFirst = explain(SegmentRepository.candidateQuery(sql, EventFilter.everything(5, 9_000), 0,
                previous, 32, false));
        assertTrue(oldestFirst.contains("USING INDEX hawkeye_segment_first (first_id>? AND first_id<?)"), oldestFirst);
        assertTrue(!oldestFirst.contains("TEMP B-TREE"), "no sort: " + oldestFirst);
    }

    @Test
    public void aWalkOfTheNewestIdRangesIsAnsweredFromTheIndexInItsOrder() throws SQLException {
        Sql sql = harness.database().sql();
        String first = explain(SegmentRepository.newestRangesQuery(sql, EventFilter.everything(5, 9_000), 0, null, 64));
        assertTrue(first.contains("USING COVERING INDEX hawkeye_segment_last (last_id>?)"), first);
        assertTrue(!first.contains("TEMP B-TREE"), "no sort: " + first);

        String later = explain(SegmentRepository.newestRangesQuery(sql, EventFilter.everything(5, 9_000), 7_000,
                new SegmentRange(40, 1_000, 5_000), 256));
        assertTrue(later.contains("USING COVERING INDEX hawkeye_segment_last (last_id>? AND last_id<?)"), later);
        assertTrue(!later.contains("TEMP B-TREE"), "no sort: " + later);
    }

    @Test
    public void placeIndexRowsAreReadByPrimaryKey() throws SQLException {
        for (PlaceIndexRepository.Kind kind : PlaceIndexRepository.Kind.values()) {
            String plan = explain(PlaceIndexRepository.readQuery(harness.database().sql(), kind, 1,
                    List.of(5L, 9L, 12L)));
            assertTrue(plan.contains("SEARCH " + kind.table(harness.database().sql()) + " USING INDEX sqlite_autoindex_"
                    + kind.table(harness.database().sql()) + "_1 (world=? AND " + kind.keyColumn() + "=?)"), plan);
            assertTrue(!plan.contains("SCAN"), plan);
            String trim = explain(PlaceIndexRepository.deleteOlderQuery(harness.database().sql(), kind, 40));
            assertTrue(trim.contains(kind.table(harness.database().sql()) + "_newest (newest<?)"), trim);
        }
    }

    @Test
    public void actorIndexRowsAreReadByPrimaryKeyAndTrimmedByNewest() throws SQLException {
        Sql sql = harness.database().sql();
        String plan = explain(ActorIndexRepository.readQuery(sql, List.of(5, 9, 12)));
        assertTrue(plan.contains("SEARCH " + sql.tables().actorSegment() + " USING INTEGER PRIMARY KEY (rowid=?)"),
                plan);
        assertTrue(!plan.contains("SCAN"), plan);
        String trim = explain(ActorIndexRepository.deleteOlderQuery(sql, 40));
        assertTrue(trim.contains(sql.tables().actorSegment() + "_newest (newest<?)"), trim);
    }

    @Test
    public void plannedSegmentsAreReadByPrimaryKeyRanges() throws SQLException {
        String ranges = explain(SegmentRepository.rangeQuery(harness.database().sql(), IdRuns.of(3, 7, 8, 9, 40), 0,
                3));
        assertTrue(ranges.contains("USING INTEGER PRIMARY KEY (rowid=?)"), ranges);
        assertTrue(ranges.contains("USING INTEGER PRIMARY KEY (rowid>? AND rowid<?)"), ranges);
        assertTrue(!ranges.contains("SCAN"), ranges);
        String rows = explain(SegmentRepository.rowsQuery(harness.database().sql(), List.of(4L, 2L, 9L)));
        assertTrue(rows.contains("USING INTEGER PRIMARY KEY (rowid=?)"), rows);
        assertTrue(!rows.contains("SCAN"), rows);
    }

    @Test
    public void theIdRangesOfEverySegmentComeFromTheIndexAlone() throws SQLException {
        String plan = explain(SegmentRepository.everyRangeQuery(harness.database().sql(), 5, 9_000));
        assertTrue(plan.contains("USING COVERING INDEX hawkeye_segment_last (last_id>?)"), plan);
        String all = explain(SegmentRepository.everyRangeQuery(harness.database().sql(), 0, Long.MAX_VALUE));
        assertTrue(all.contains("USING COVERING INDEX hawkeye_segment_last (last_id>?)"), all);
    }

    @Test
    public void aScatteredSetOfSegmentsReadsTheSameRangesAsAFewRuns() throws SQLException {
        Sql sql = harness.database().sql();
        SegmentRepository repository = new SegmentRepository();
        List<Long> stored = new ArrayList<>();
        harness.database().transaction(connection -> {
            for (int segment = 0; segment < 200; segment++) {
                try (PreparedStatement statement = connection.prepareStatement("INSERT INTO " + sql.tables().segment()
                        + " (first_id, last_id, events, header, body) VALUES (?, ?, 1, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    statement.setLong(1, 1_000L * segment);
                    statement.setLong(2, 1_000L * segment + 999);
                    statement.setBytes(3, new byte[] {1});
                    statement.setBytes(4, new byte[] {2});
                    statement.executeUpdate();
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        keys.next();
                        stored.add(keys.getLong(1));
                    }
                }
            }
            return null;
        });
        IdRuns scattered = new IdRuns();
        IdRuns few = new IdRuns();
        IdRuns oneLongRun = IdRuns.range(stored.get(10), stored.get(150));
        for (int index = 0; index < stored.size(); index++) {
            if (index % 2 == 0 && index < 80) {
                scattered.add(stored.get(index));
            }
            if (index < 6) {
                few.add(stored.get(index));
            }
        }
        scattered.add(stored.getLast() + 500);
        assertTrue(scattered.runCount() > SegmentRepository.SCATTERED_RUNS);
        assertTrue(few.runCount() <= SegmentRepository.SCATTERED_RUNS && few.count() <= SegmentRepository.SCATTERED_IDS);
        assertTrue(oneLongRun.runCount() == 1 && oneLongRun.count() > SegmentRepository.SCATTERED_IDS);

        List<SegmentRange> wide = harness.database().read(connection -> repository.ranges(connection, sql, scattered,
                0, Long.MAX_VALUE));
        List<SegmentRange> narrow = harness.database().read(connection -> repository.ranges(connection, sql, few, 0,
                Long.MAX_VALUE));
        assertEquals(40, wide.size(), "an id that is not stored is left out");
        for (int index = 0; index < wide.size(); index++) {
            assertEquals(new SegmentRange(stored.get(2 * index), 2_000L * index, 2_000L * index + 999), wide.get(index));
        }
        assertEquals(6, narrow.size());
        for (int index = 0; index < narrow.size(); index++) {
            assertEquals(new SegmentRange(stored.get(index), 1_000L * index, 1_000L * index + 999), narrow.get(index));
        }

        // Both paths give the same ranges for the same set and id range: the index scan for a set of many
        // ids, the primary key for the same set split into batches of a few.
        long minId = 20_500;
        long maxId = 120_200;
        for (IdRuns set : List.of(scattered, few, oneLongRun)) {
            List<SegmentRange> bounded = harness.database().read(connection -> repository.ranges(connection, sql,
                    set, minId, maxId));
            List<SegmentRange> bySeeks = new ArrayList<>();
            for (long id : toList(set)) {
                bySeeks.addAll(harness.database().read(connection -> repository.ranges(connection, sql, IdRuns.of(id),
                        minId, maxId)));
            }
            assertEquals(bySeeks, bounded, set.toString());
            assertTrue(bounded.stream().allMatch(range -> range.lastId() >= minId && range.firstId() <= maxId));
        }
        assertEquals(101, harness.database().read(connection -> repository.ranges(connection, sql, oneLongRun, minId,
                maxId)).size(), "segments 20 to 120 of the run overlap the id range");
    }

    private static List<Long> toList(IdRuns set) {
        List<Long> out = new ArrayList<>();
        set.forEach(out::add);
        return out;
    }

    private static EventFilter filter(Set<Integer> worlds, List<EventFilter.WorldRange> ranges,
            EventFilter.Bounds bounds, Set<Integer> actors) {
        return new EventFilter(0, Long.MAX_VALUE, worlds, ranges, bounds, Set.of(), actors, Set.of(), Set.of(),
                Set.of(), Set.of(), RollbackState.ANY);
    }

    private String plan(EventFilter filter) throws SQLException {
        return explain(EventSql.select(harness.database().tables(), filter, 0, 10, true));
    }

    private String explain(BoundSql query) throws SQLException {
        return harness.database().read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("EXPLAIN QUERY PLAN " + query.sql())) {
                query.bind(statement);
                StringBuilder plan = new StringBuilder();
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        plan.append(rows.getString("detail")).append('\n');
                    }
                }
                return plan.toString();
            }
        });
    }
}
