package gg.moonrise.hawkeye.ui.lookup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.config.message.LookupMessages;
import gg.moonrise.hawkeye.config.message.MessagesConfig;
import gg.moonrise.hawkeye.core.service.lookup.LookupOutcome;
import gg.moonrise.hawkeye.core.service.lookup.LookupTotal;
import gg.moonrise.hawkeye.core.service.lookup.LookupWarning;
import gg.moonrise.hawkeye.core.service.lookup.page.PageResult;
import gg.moonrise.hawkeye.core.service.lookup.page.PageView;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.entity.query.Area;
import gg.moonrise.hawkeye.entity.query.LookupQuery;
import gg.moonrise.hawkeye.util.position.BlockPositions;
import gg.moonrise.hawkeye.util.event.EventIds;
import java.lang.reflect.Field;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

public class LookupLinesTest {

    private static final Instant NOW = Instant.parse("2026-11-16T12:00:00Z");
    private static final LookupQuery EVERYWHERE = LookupQuery.at(new Area.Everywhere());
    private static final LookupQuery BLOCK = LookupQuery.at(new Area.Block("world", 4, 70, -9));

    private final MessagesConfig messages = new MessagesConfig();
    private final LookupLines lines = new LookupLines(messages, new EventLines(messages.getLookup(),
            messages.getActions(), "survival", NOW, ZoneOffset.UTC));

    @Test
    public void aMiddlePageHasHeaderLinesAndBothPageLinks() {
        List<Component> page = lines.page(new PageView(EVERYWHERE, 2, 2, exact(6), events(2), true, List.of()));

        assertEquals(4, page.size());
        assertEquals("----- Hawkeye results -----", EventLinesTest.plain(page.getFirst()));
        assertTrue(EventLinesTest.plain(page.get(1)).contains("broke stone"));
        Component footer = page.getLast();
        assertEquals("Page 2/3 [< prev] [next >]", EventLinesTest.plain(footer));
        assertEquals(List.of("/hawkeye page 1", "/hawkeye page 3"), EventLinesTest.commands(footer));
    }

    @Test
    public void aSinglePageHasNoFooter() {
        List<Component> page = lines.page(new PageView(EVERYWHERE, 1, 8, exact(2), events(2), false, List.of()));
        assertEquals(3, page.size());
    }

    @Test
    public void theFirstPageLinksOnlyForward() {
        List<Component> page = lines.page(new PageView(EVERYWHERE, 1, 2, new LookupTotal.Unknown(), events(2),
                true, List.of()));
        Component footer = page.getLast();
        assertEquals("Page 1/? [next >]", EventLinesTest.plain(footer).replaceAll(" +", " ").trim());
        assertEquals(List.of("/hawkeye page 2"), EventLinesTest.commands(footer));
    }

    @Test
    public void aPageAboutOneBlockHasTheInspectorHeader() {
        List<Component> page = lines.page(new PageView(BLOCK, 1, 8, exact(1), events(1), false, List.of()));
        assertEquals("----- Hawkeye 4 70 -9 -----", EventLinesTest.plain(page.getFirst()));
    }

    @Test
    public void emptyPagesSayNothingWasFound() {
        assertEquals(List.of("No results found."), plain(lines.page(new PageView(EVERYWHERE, 1, 8, exact(0), List.of(),
                false, List.of()))));
        assertEquals(List.of("No history for this block."), plain(lines.page(new PageView(BLOCK, 1, 8, exact(0), List.of(),
                false, List.of()))));
    }

    @Test
    public void unreadableShardsFollowThePage() {
        List<Component> page = lines.page(new PageView(EVERYWHERE, 1, 8, exact(1), events(1), false,
                List.of(new LookupWarning("east", "connection refused"))));
        assertEquals("Shard east could not be read: connection refused", EventLinesTest.plain(page.getLast()));
    }

    @Test
    public void everyOtherOutcomeHasItsMessage() {
        assertEquals(List.of("You have no recent lookup to page through."),
                plain(lines.page(new PageResult.NoSession())));
        assertEquals(List.of("There are only 3 page(s)."), plain(lines.page(new PageResult.OutOfRange(3))));
        assertEquals(List.of("Hawkeye's database is not ready yet; try again in a moment."),
                plain(lines.page(new PageResult.Unavailable(new LookupOutcome.NotReady()))));
        assertEquals(List.of("That failed: busy"),
                plain(lines.page(new PageResult.Unavailable(new LookupOutcome.Failed("busy")))));
        assertEquals(List.of("The lookup took too long; narrow it with t:, r: or u:."),
                plain(lines.page(new PageResult.Unavailable(new LookupOutcome.TimedOut()))));
    }

    @Test
    public void aCountShowsTheTotalAndWarnings() {
        assertEquals(List.of("Matching events: 42", "Shard east could not be read: down"),
                plain(lines.count(new LookupOutcome.Count(42, true, List.of(new LookupWarning("east", "down"))))));
        assertEquals(List.of("The lookup took too long; narrow it with t:, r: or u:."),
                plain(lines.count(new LookupOutcome.TimedOut())));
        assertEquals(List.of("Matching events: 42+"), plain(lines.count(new LookupOutcome.Count(42, false, List.of()))));
    }

    @Test
    public void exactLowerBoundAndUnknownTotalsEachHaveTheirMessage() {
        LookupLines custom = linesWith("<page>/<pages> of <total>");
        assertEquals("1/3 of 6", EventLinesTest.plain(custom.page(new PageView(EVERYWHERE, 1, 2, exact(6), events(2),
                true, List.of())).getFirst()));
        assertEquals("1/8+ of 16+", EventLinesTest.plain(custom.page(new PageView(EVERYWHERE, 1, 2,
                new LookupTotal.AtLeast(16), events(2), true, List.of())).getFirst()));
        assertEquals("1/? of ?", EventLinesTest.plain(custom.page(new PageView(EVERYWHERE, 1, 2,
                new LookupTotal.Unknown(), events(2), true, List.of())).getFirst()));

        List<Component> inspected = custom.page(new PageView(BLOCK, 1, 10, new LookupTotal.AtLeast(160), events(10),
                true, List.of()));
        assertEquals("----- Hawkeye 4 70 -9 -----", EventLinesTest.plain(inspected.getFirst()));
        assertEquals("Page 1/16+ [next >]", EventLinesTest.plain(inspected.getLast()).replaceAll(" +", " ").trim());
    }

    @Test
    public void theLowerBoundMessageIsConfigurable() throws Exception {
        MessagesConfig changed = new MessagesConfig();
        Field atLeast = LookupMessages.class.getDeclaredField("atLeast");
        atLeast.setAccessible(true);
        atLeast.set(changed.getLookup(), "<value> or more");
        LookupLines custom = new LookupLines(changed, new EventLines(changed.getLookup(), changed.getActions(),
                "survival", NOW, ZoneOffset.UTC));
        assertEquals(List.of("Matching events: 16 or more"), plain(custom.count(new LookupOutcome.Count(16, false,
                List.of()))));
    }

    /** Lines whose results header is {@code header}. */
    private static LookupLines linesWith(String header) {
        MessagesConfig custom = new MessagesConfig();
        try {
            Field field = LookupMessages.class.getDeclaredField("header");
            field.setAccessible(true);
            field.set(custom.getLookup(), header);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return new LookupLines(custom, new EventLines(custom.getLookup(), custom.getActions(), "survival", NOW,
                ZoneOffset.UTC));
    }

    private static LookupTotal exact(long events) {
        return new LookupTotal.Exact(events);
    }

    private static List<StoredEvent> events(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> new StoredEvent(EventIds.compose(NOW.toEpochMilli() - i * 1000L, 1, 0), "survival",
                        "world", BlockPositions.pack(i, 64, 0), Actor.source("tnt"), Action.BLOCK_BREAK,
                        "minecraft:stone", "minecraft:air", 0, false, null))
                .toList();
    }

    private static List<String> plain(List<Component> components) {
        return components.stream().map(EventLinesTest::plain).toList();
    }
}
