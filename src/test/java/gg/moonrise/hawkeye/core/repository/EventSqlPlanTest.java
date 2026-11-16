package gg.moonrise.hawkeye.core.repository;

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
    public void placeIndexRowsAreReadByPrimaryKey() throws SQLException {
        for (PlaceIndexRepository.Kind kind : PlaceIndexRepository.Kind.values()) {
            String plan = explain(PlaceIndexRepository.readQuery(harness.database().sql(), kind, 1,
                    List.of(5L, 9L, 12L)));
            assertTrue(plan.contains("SEARCH " + kind.table(harness.database().sql()) + " USING PRIMARY KEY (world=? AND "
                    + kind.keyColumn() + "=?)"), plan);
            String trim = explain(PlaceIndexRepository.deleteOlderQuery(harness.database().sql(), kind, 40));
            assertTrue(trim.contains("_newest (newest<?)"), trim);
        }
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
