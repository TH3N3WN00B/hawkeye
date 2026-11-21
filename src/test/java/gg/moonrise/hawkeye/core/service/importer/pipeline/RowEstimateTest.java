package gg.moonrise.hawkeye.core.service.importer.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;

import gg.moonrise.hawkeye.entity.importer.SourceCursor;
import gg.moonrise.hawkeye.entity.importer.SourceTable;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

public class RowEstimateTest {

    private static final List<SourceTable> TABLES = List.of(SourceTable.BLOCK, SourceTable.CHAT);

    private final AtomicBoolean failing = new AtomicBoolean();
    private final AtomicLong perTable = new AtomicLong(5);
    private final RowEstimate estimate = new RowEstimate((table, after) -> {
        if (failing.get()) {
            throw new SQLTimeoutException("the count timed out");
        }
        return perTable.get();
    }, "clickhouse default@ch:8123/default");

    @Test
    public void aFailureBeforeTheFirstCountEstimatesNothing() {
        failing.set(true);

        assertEquals(0, estimate.refresh(TABLES, table -> SourceCursor.START));
        assertEquals(0, estimate.last());
    }

    @Test
    public void aFailureKeepsThePreviousEstimate() {
        assertEquals(10, estimate.refresh(TABLES, table -> SourceCursor.START));

        failing.set(true);
        perTable.set(1);
        assertEquals(10, estimate.refresh(TABLES, table -> SourceCursor.START));
        assertEquals(10, estimate.refresh(TABLES, table -> SourceCursor.START), "every failure, not only the first");

        failing.set(false);
        assertEquals(2, estimate.refresh(TABLES, table -> SourceCursor.START));
        assertEquals(2, estimate.last());
    }

    @Test
    public void aTableFailingHalfwayDoesNotCountTheOthersAlone() {
        RowEstimate partial = new RowEstimate((table, after) -> {
            if (table == SourceTable.CHAT) {
                throw new SQLException("chat cannot be counted");
            }
            return 7;
        }, "test");

        assertEquals(0, partial.refresh(TABLES, table -> SourceCursor.START));
    }
}
