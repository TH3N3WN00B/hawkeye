package gg.moonrise.hawkeye.core.service.importer.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.entity.importer.source.SourceCursor;
import gg.moonrise.hawkeye.entity.importer.source.SourceRow;
import gg.moonrise.hawkeye.entity.importer.source.SourceTable;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

public class SourceMergeTest {

    /** Later than any sensible row of these tests. */
    private static final long CEILING = 1_000;

    /** A horizon that leaves nothing for later: rows up to the ceiling are read, later ones come first. */
    private static final RunHorizon OPEN = new RunHorizon(CEILING, CEILING);

    private final Map<SourceTable, List<SourceRow>> tables = new EnumMap<>(SourceTable.class);
    private final List<String> reads = new ArrayList<>();

    @Test
    public void rowsComeInTimeOrderWithTiesByTable() throws SQLException {
        rows(SourceTable.SESSION, 10, 20, 30);
        rows(SourceTable.BLOCK, 20, 25);
        rows(SourceTable.CHAT, 5, 20);

        assertEquals(List.of("chat@5", "session@10", "block@20", "chat@20", "session@20", "block@25", "session@30"),
                drain(merge(Map.of(), 2)));
    }

    @Test
    public void eachTableKeepsItsOwnOrder() throws SQLException {
        rows(SourceTable.BLOCK, 10, 30, 20);
        rows(SourceTable.ITEM, 15, 25);

        assertEquals(List.of("block@10", "item@15", "item@25", "block@30", "block@20"), drain(merge(Map.of(), 1)));
    }

    @Test
    public void tablesAreReadInPagesUntilAReadIsEmpty() throws SQLException {
        rows(SourceTable.BLOCK, 1, 2, 3, 4, 5);
        rows(SourceTable.SIGN);

        SourceMerge merge = merge(Map.of(), 2);
        assertEquals(5, drain(merge).size());
        assertEquals(List.of("block>0", "sign>0", "block>2", "block>4", "block>5"), reads);
        assertTrue(merge.peek().isEmpty());
        assertThrows(IllegalStateException.class, merge::next);
    }

    @Test
    public void unevenTablesMergeToTheEnd() throws SQLException {
        rows(SourceTable.BLOCK, 1, 2, 3, 4, 5, 6, 7, 8, 9);
        rows(SourceTable.COMMAND, 4);

        assertEquals(List.of("block@1", "block@2", "block@3", "block@4", "command@4", "block@5", "block@6", "block@7",
                "block@8", "block@9"), drain(merge(Map.of(), 3)));
    }

    @Test
    public void readingResumesAfterEachTablesCursor() throws SQLException {
        rows(SourceTable.BLOCK, 10, 20, 30);
        rows(SourceTable.CHAT, 15, 25);

        Map<SourceTable, SourceCursor> cursors = Map.of(SourceTable.BLOCK, new SourceCursor(20, 2),
                SourceTable.CHAT, new SourceCursor(15, 1));
        assertEquals(List.of("chat@25", "block@30"), drain(merge(cursors, 10)));
    }

    @Test
    public void rowsBeyondTheCeilingComeFirstSoTheyHoldNothingBack() throws SQLException {
        rows(SourceTable.BLOCK, 10, CEILING + 500, 20);
        rows(SourceTable.CHAT, 15, 30);

        assertEquals(List.of("block@10", "block@" + (CEILING + 500), "chat@15", "block@20", "chat@30"),
                drain(merge(Map.of(), 1)));
    }

    @Test
    public void aRowAtTheCeilingKeepsItsPlace() throws SQLException {
        rows(SourceTable.BLOCK, 10, CEILING, 20);
        rows(SourceTable.CHAT, 15);

        assertEquals(List.of("block@10", "chat@15", "block@" + CEILING, "block@20"), drain(merge(Map.of(), 1)));
    }

    @Test
    public void theMergeStopsAtTheFirstRowPastTheHorizon() throws SQLException {
        rows(SourceTable.BLOCK, 10, 30, 15);
        rows(SourceTable.CHAT, 5, 25);

        SourceMerge merge = merge(Map.of(), 10, new RunHorizon(20, CEILING));
        assertEquals(List.of("chat@5", "block@10"), drain(merge));
        assertTrue(merge.held(), "rows are left for a later run");
        assertThrows(IllegalStateException.class, merge::next);
    }

    @Test
    public void aMergeReadToItsEndHoldsNothing() throws SQLException {
        rows(SourceTable.BLOCK, 10);
        SourceMerge merge = merge(Map.of(), 10, OPEN);

        drain(merge);
        assertFalse(merge.held());
    }

    @Test
    public void inTimeOrderARowFarInTheFutureIsNeverConsumed() throws SQLException {
        long start = 100;
        rows(SourceTable.BLOCK, 10, 20, 1_000_000_000, 30);
        rows(SourceTable.CHAT, 15);

        SourceMerge first = timeOrder(Map.of(), RunHorizon.of(start * 1000, 0, true));
        List<SourceRow> consumed = new ArrayList<>();
        while (first.peek().isPresent()) {
            consumed.add(first.next());
        }
        assertEquals(List.of(10L, 15L, 20L, 30L), consumed.stream().map(SourceRow::time).toList());
        assertTrue(first.held(), "the future row stays unread");

        // CoreProtect logs on; the next run continues from the cursors and reads the new rows.
        add(SourceTable.BLOCK, 50, 60);
        add(SourceTable.CHAT, 55);
        Map<SourceTable, SourceCursor> cursors = new EnumMap<>(SourceTable.class);
        consumed.forEach(row -> cursors.put(row.table(), row.cursor()));
        SourceMerge next = timeOrder(cursors, RunHorizon.of(200_000, 0, true));
        assertEquals(List.of("block@50", "chat@55", "block@60"), drain(next));
        assertTrue(next.held());
    }

    @Test
    public void inRowidOrderARowFarInTheFutureIsConsumedFirst() throws SQLException {
        rows(SourceTable.BLOCK, 10, 1_000_000_000, 20);

        SourceMerge merge = merge(Map.of(), 10, RunHorizon.of(100_000, 0, false));
        assertEquals(List.of("block@10", "block@1000000000", "block@20"), drain(merge),
                "it cannot hold back the row behind it");
        assertFalse(merge.held());
    }

    @Test
    public void theSettleTimeMovesTheHorizonBack() {
        RunHorizon horizon = RunHorizon.of(1_000_500, 120, false);
        assertEquals(880, horizon.seconds());
        assertEquals(880 + RunHorizon.FUTURE_SECONDS, horizon.ceiling());
        assertEquals(880_999, horizon.lastMillis());
        assertEquals(Long.MAX_VALUE, RunHorizon.of(1_000_500, 120, true).ceiling(), "no ceiling in time order");
        assertFalse(horizon.leftForLater(row(SourceTable.CHAT, 1, 880)));
        assertTrue(horizon.leftForLater(row(SourceTable.CHAT, 1, 881)));
        assertFalse(horizon.leftForLater(row(SourceTable.CHAT, 1, 881 + RunHorizon.FUTURE_SECONDS)),
                "beyond the ceiling: consumed and skipped instead");
    }

    @Test
    public void peekDoesNotConsume() throws SQLException {
        rows(SourceTable.BLOCK, 10);
        SourceMerge merge = merge(Map.of(), 5);

        assertEquals(10, merge.peek().orElseThrow().time());
        assertEquals(10, merge.peek().orElseThrow().time());
        assertEquals(10, merge.next().time());
        assertTrue(merge.peek().isEmpty());
    }

    /** Rows of a table with rowids 1, 2, ... and the given times. */
    private void rows(SourceTable table, long... times) {
        tables.put(table, new ArrayList<>());
        add(table, times);
    }

    /** Rows appended to a table, with the next rowids. */
    private void add(SourceTable table, long... times) {
        List<SourceRow> list = tables.get(table);
        for (long time : times) {
            list.add(row(table, list.size() + 1, time));
        }
    }

    private static SourceRow row(SourceTable table, long rowId, long time) {
        return switch (table) {
            case SESSION -> new SourceRow.Session(rowId, time, 1, 1, 0, 0, 0, 1);
            case CHAT, COMMAND -> new SourceRow.Message(table, rowId, time, 1, 1, 0, 0, 0, "");
            case ITEM -> new SourceRow.Item(rowId, time, 1, 1, 0, 0, 0, 1, 1, null, 2);
            case SIGN -> new SourceRow.Sign(rowId, time, 1, 1, 0, 0, 0, 1, 0, List.of("", "", "", "", "", "", "", ""),
                    0);
            default -> new SourceRow.Block(rowId, time, 1, 1, 0, 0, 0, 1, 0, new int[0], 1, 0, null);
        };
    }

    private SourceMerge merge(Map<SourceTable, SourceCursor> cursors, int page) throws SQLException {
        return merge(cursors, page, OPEN);
    }

    /** Reads in rowid order after the cursor, like the SQLite reader. */
    private SourceMerge merge(Map<SourceTable, SourceCursor> cursors, int page, RunHorizon horizon)
            throws SQLException {
        Set<SourceTable> present = tables.keySet();
        return new SourceMerge((table, after, limit) -> {
            reads.add(table.tableName() + ">" + after.rowId());
            return tables.get(table).stream().filter(row -> row.rowId() > after.rowId()).limit(limit).toList();
        }, present, table -> cursors.getOrDefault(table, SourceCursor.START), page, horizon);
    }

    /** Reads in {@code (time, rowid)} keyset order after the cursor, like the ClickHouse reader, two rows a page. */
    private SourceMerge timeOrder(Map<SourceTable, SourceCursor> cursors, RunHorizon horizon) throws SQLException {
        Comparator<SourceRow> order = Comparator.comparingLong(SourceRow::time).thenComparingLong(SourceRow::rowId);
        return new SourceMerge((table, after, limit) -> tables.get(table).stream()
                .filter(row -> row.time() > after.time() || row.time() == after.time() && row.rowId() > after.rowId())
                .sorted(order).limit(limit).toList(),
                tables.keySet(), table -> cursors.getOrDefault(table, SourceCursor.START), 2, horizon);
    }

    private static List<String> drain(SourceMerge merge) throws SQLException {
        List<String> out = new ArrayList<>();
        while (merge.peek().isPresent()) {
            SourceRow row = merge.next();
            out.add(row.table().tableName() + "@" + row.time());
        }
        return out;
    }
}
