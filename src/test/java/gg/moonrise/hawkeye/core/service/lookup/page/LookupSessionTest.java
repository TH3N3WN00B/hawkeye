package gg.moonrise.hawkeye.core.service.lookup.page;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.core.service.lookup.LookupTotal;
import gg.moonrise.hawkeye.entity.query.Area;
import gg.moonrise.hawkeye.entity.query.LookupQuery;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

public class LookupSessionTest {

    private static final LookupQuery QUERY = LookupQuery.at(new Area.Everywhere());

    @Test
    public void pagesComeFromTheTotal() {
        assertEquals(OptionalInt.of(3), new LookupSession(QUERY, 8, exact(20), List.of(0L)).pages());
        assertEquals(OptionalInt.of(1), new LookupSession(QUERY, 8, exact(0), List.of(0L)).pages());
        assertEquals(OptionalInt.of(2), new LookupSession(QUERY, 8, exact(16), List.of(0L)).pages());
        assertTrue(new LookupSession(QUERY, 8, new LookupTotal.Unknown(), List.of(0L)).pages().isEmpty());
    }

    @Test
    public void pagesNeverFallBelowThePagesSeen() {
        assertEquals(OptionalInt.of(3), new LookupSession(QUERY, 8, exact(5), List.of(0L, 10L, 20L)).pages());
    }

    @Test
    public void onlyTheNextUnknownPageStartIsRecorded() {
        LookupSession session = new LookupSession(QUERY, 8, exact(30), List.of(100L));

        LookupSession next = session.withStart(2, 90L);
        assertEquals(List.of(100L, 90L), next.starts());
        assertEquals(90L, next.start(2));
        assertEquals(2, next.knownPages());

        assertSame(next, next.withStart(2, 85L));
        assertSame(next, next.withStart(5, 40L));
    }

    @Test
    public void aSessionKnowsAtLeastItsFirstPage() {
        assertThrows(IllegalArgumentException.class, () -> new LookupSession(QUERY, 8, exact(0), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new LookupSession(QUERY, 0, exact(0), List.of(0L)));
    }

    @Test
    public void aViewCountsItsOwnPageAndTheNext() {
        PageView last = new PageView(QUERY, 4, 8, exact(20), List.of(), true, List.of());
        assertEquals(OptionalInt.of(5), last.pages());
        PageView unknown = new PageView(QUERY, 1, 8, new LookupTotal.Unknown(), List.of(), false, List.of());
        assertTrue(unknown.pages().isEmpty());
    }

    @Test
    public void aLowerBoundIsNoPageCount() {
        assertTrue(new LookupSession(QUERY, 8, new LookupTotal.AtLeast(160), List.of(0L)).pages().isEmpty());
        PageView view = new PageView(QUERY, 1, 8, new LookupTotal.AtLeast(160), List.of(), true, List.of());
        assertTrue(view.pages().isEmpty());
        assertEquals(20, view.pagesFor(160));
    }

    @Test
    public void aSessionTakesANewTotal() {
        LookupSession session = new LookupSession(QUERY, 8, new LookupTotal.AtLeast(16), List.of(0L));
        assertSame(session, session.withTotal(new LookupTotal.AtLeast(16)));
        assertEquals(OptionalInt.of(4), session.withTotal(exact(30)).pages());
    }

    private static LookupTotal exact(long events) {
        return new LookupTotal.Exact(events);
    }
}
