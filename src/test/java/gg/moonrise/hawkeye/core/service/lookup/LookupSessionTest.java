package gg.moonrise.hawkeye.core.service.lookup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.entity.query.Area;
import gg.moonrise.hawkeye.entity.query.LookupQuery;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

public class LookupSessionTest {

    private static final LookupQuery QUERY = LookupQuery.at(new Area.Everywhere());

    @Test
    public void pagesComeFromTheTotal() {
        assertEquals(OptionalInt.of(3), new LookupSession(QUERY, 8, 20, List.of(0L)).pages());
        assertEquals(OptionalInt.of(1), new LookupSession(QUERY, 8, 0, List.of(0L)).pages());
        assertEquals(OptionalInt.of(2), new LookupSession(QUERY, 8, 16, List.of(0L)).pages());
        assertTrue(new LookupSession(QUERY, 8, LookupSession.UNKNOWN_TOTAL, List.of(0L)).pages().isEmpty());
    }

    @Test
    public void pagesNeverFallBelowThePagesSeen() {
        assertEquals(OptionalInt.of(3), new LookupSession(QUERY, 8, 5, List.of(0L, 10L, 20L)).pages());
    }

    @Test
    public void onlyTheNextUnknownPageStartIsRecorded() {
        LookupSession session = new LookupSession(QUERY, 8, 30, List.of(100L));

        LookupSession next = session.withStart(2, 90L);
        assertEquals(List.of(100L, 90L), next.starts());
        assertEquals(90L, next.start(2));
        assertEquals(2, next.knownPages());

        assertSame(next, next.withStart(2, 85L));
        assertSame(next, next.withStart(5, 40L));
    }

    @Test
    public void aSessionKnowsAtLeastItsFirstPage() {
        assertThrows(IllegalArgumentException.class, () -> new LookupSession(QUERY, 8, 0, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new LookupSession(QUERY, 0, 0, List.of(0L)));
    }

    @Test
    public void aViewCountsItsOwnPageAndTheNext() {
        PageView last = new PageView(QUERY, 4, 8, 20, List.of(), true, List.of());
        assertEquals(OptionalInt.of(5), last.pages());
        PageView unknown = new PageView(QUERY, 1, 8, LookupSession.UNKNOWN_TOTAL, List.of(), false, List.of());
        assertTrue(unknown.pages().isEmpty());
        assertTrue(unknown.knownTotal().isEmpty());
    }
}
