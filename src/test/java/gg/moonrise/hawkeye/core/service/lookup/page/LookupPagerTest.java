package gg.moonrise.hawkeye.core.service.lookup.page;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.config.file.ArchiveSettings;
import gg.moonrise.hawkeye.core.service.capture.Actors;
import gg.moonrise.hawkeye.core.service.lookup.LookupFixtures;
import gg.moonrise.hawkeye.core.service.lookup.LookupOutcome;
import gg.moonrise.hawkeye.core.service.lookup.LookupService;
import gg.moonrise.hawkeye.core.service.lookup.LookupTotal;
import gg.moonrise.hawkeye.core.storage.archive.Archive;
import gg.moonrise.hawkeye.core.storage.shard.Shard;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.entity.query.Area;
import gg.moonrise.hawkeye.entity.query.LookupQuery;
import gg.moonrise.hawkeye.support.TestPlugins;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/** Opening lookups and turning their pages, against real storage. */
public class LookupPagerTest {

    private static final LookupQuery EVERYWHERE = LookupQuery.at(new Area.Everywhere());

    private ServerMock server;
    private World world;
    private LookupPager pager;
    private CommandSender console;

    @BeforeEach
    public void setUp() {
        server = MockBukkit.mock();
        TestPlugins.load();
        TestPlugins.awaitStorage();
        world = server.addSimpleWorld("world");
        pager = TestPlugins.bean(LookupPager.class);
        console = server.getConsoleSender();
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void openingShowsPageOneWithTheTotal() throws Exception {
        LookupFixtures.breaks(Actors.NATURE, world, 20);

        PageView view = shown(pager.open(console, EVERYWHERE, 8));

        assertEquals(1, view.page());
        assertEquals(new LookupTotal.Exact(20), view.total());
        assertEquals(OptionalInt.of(3), view.pages());
        assertEquals(8, view.events().size());
        assertTrue(view.hasMore());
        LookupSession session = TestPlugins.bean(LookupSessions.class).get(console).orElseThrow();
        assertEquals(2, session.knownPages());
        assertEquals(view.events().getFirst().id() + 1, session.start(1));
        assertEquals(view.events().getLast().id(), session.start(2));
    }

    @Test
    public void pagesFollowEachOtherAndEndAtTheLast() throws Exception {
        LookupFixtures.breaks(Actors.NATURE, world, 20);
        PageView first = shown(pager.open(console, EVERYWHERE, 8));

        PageView second = shown(pager.page(console, 2));
        PageView third = shown(pager.page(console, 3));

        assertEquals(List.of(11, 3), List.of(second.events().getFirst().x(), third.events().getFirst().x()));
        assertTrue(second.hasMore());
        assertFalse(third.hasMore());
        assertEquals(first.events(), shown(pager.page(console, 1)).events());
        assertEquals(new PageResult.OutOfRange(3), pager.page(console, 4).get(20, TimeUnit.SECONDS));
    }

    @Test
    public void aPageBeyondTheKnownOnesIsReachedByWalking() throws Exception {
        LookupFixtures.breaks(Actors.NATURE, world, 30);
        shown(pager.open(console, EVERYWHERE, 4));

        PageView sixth = shown(pager.page(console, 6));

        assertEquals(List.of(9, 8, 7, 6), sixth.events().stream().map(StoredEvent::x).toList());
        assertTrue(sixth.hasMore());
        LookupSession session = TestPlugins.bean(LookupSessions.class).get(console).orElseThrow();
        assertEquals(7, session.knownPages());
        assertEquals(List.of(13, 12, 11, 10), shown(pager.page(console, 5)).events().stream()
                .map(StoredEvent::x).toList());
    }

    @Test
    public void aLongJumpWalksInBatches() throws Exception {
        LookupFixtures.breaks(Actors.NATURE, world, 2_500);
        TestPlugins.flush();
        shown(pager.open(console, EVERYWHERE, 1));

        PageView page = shown(pager.page(console, 2_200));

        assertEquals(List.of(300), page.events().stream().map(StoredEvent::x).toList());
        assertEquals(2_201, TestPlugins.bean(LookupSessions.class).get(console).orElseThrow().knownPages());
    }

    @Test
    public void pageOneStaysPinnedWhenNewEventsArrive() throws Exception {
        LookupFixtures.breaks(Actors.NATURE, world, 5);
        PageView before = shown(pager.open(console, EVERYWHERE, 8));
        LookupFixtures.breaks(Actors.FIRE, world, 3);

        assertEquals(before.events(), shown(pager.page(console, 1)).events());
    }

    @Test
    public void openingALookupOverManySegmentsCountsOnlyTheNewestAndShowsALowerBound() throws Exception {
        archiveInSegmentsOfFive(100);

        PageView first = shown(pager.open(console, EVERYWHERE, 4));

        assertEquals(new LookupTotal.AtLeast(5L * LookupPager.COUNT_SEGMENTS), first.total());
        assertTrue(first.pages().isEmpty(), "a lower bound is no page count");
        assertEquals(List.of(99, 98, 97, 96), first.events().stream().map(StoredEvent::x).toList());
        assertTrue(first.hasMore());
        LookupOutcome exact = TestPlugins.bean(LookupService.class).count(EVERYWHERE).get(20, TimeUnit.SECONDS);
        assertEquals(new LookupOutcome.Count(100, true, List.of()), exact, "#count stays exact");
    }

    @Test
    public void pagingPastALowerBoundWalksOnAndEndsWhereTheRowsDo() throws Exception {
        archiveInSegmentsOfFive(100);
        shown(pager.open(console, EVERYWHERE, 4));

        PageView beyond = shown(pager.page(console, 22));
        assertEquals(List.of(15, 14, 13, 12), beyond.events().stream().map(StoredEvent::x).toList());
        assertTrue(beyond.hasMore());
        assertInstanceOf(LookupTotal.AtLeast.class, beyond.total());

        PageView last = shown(pager.page(console, 25));
        assertEquals(List.of(3, 2, 1, 0), last.events().stream().map(StoredEvent::x).toList());
        assertFalse(last.hasMore());
        assertEquals(new LookupTotal.Exact(100), last.total(), "the rows ended: the total is exact");
        assertEquals(OptionalInt.of(25), last.pages());
        assertEquals(new PageResult.OutOfRange(25), pager.page(console, 26).get(20, TimeUnit.SECONDS));
    }

    @Test
    public void walkingBeyondTheEndOfALowerBoundIsOutOfRange() throws Exception {
        archiveInSegmentsOfFive(100);
        shown(pager.open(console, EVERYWHERE, 4));

        assertEquals(new PageResult.OutOfRange(25), pager.page(console, 40).get(20, TimeUnit.SECONDS));
        assertEquals(OptionalInt.of(25), TestPlugins.bean(LookupSessions.class).get(console).orElseThrow().pages());
    }

    @Test
    public void aSmallArchivedResultHasItsExactTotal() throws Exception {
        archiveInSegmentsOfFive(40);

        PageView first = shown(pager.open(console, EVERYWHERE, 8));

        assertEquals(new LookupTotal.Exact(40), first.total());
        assertEquals(OptionalInt.of(5), first.pages());
    }

    @Test
    public void aLowerBoundIsNeverBelowWhatPageOneShows() {
        List<StoredEvent> ten = shownEvents(10);
        LookupOutcome.Page more = new LookupOutcome.Page(ten, true, List.of());
        assertEquals(new LookupTotal.AtLeast(11), LookupPager.firstTotal(new LookupTotal.AtLeast(7), more));
        assertEquals(new LookupTotal.AtLeast(500), LookupPager.firstTotal(new LookupTotal.AtLeast(500), more));
        assertEquals(new LookupTotal.Exact(10), LookupPager.firstTotal(new LookupTotal.AtLeast(7),
                new LookupOutcome.Page(ten, false, List.of())));
        assertEquals(new LookupTotal.Unknown(), LookupPager.firstTotal(new LookupTotal.Unknown(), more));
    }

    @Test
    public void pagingWithoutALookupHasNoSession() throws Exception {
        assertInstanceOf(PageResult.NoSession.class, pager.page(console, 2).get(20, TimeUnit.SECONDS));
    }

    @Test
    public void anEmptyLookupStillStartsASession() throws Exception {
        PageView view = shown(pager.open(console, EVERYWHERE, 8));
        assertTrue(view.events().isEmpty());
        assertEquals(new PageResult.OutOfRange(1), pager.page(console, 2).get(20, TimeUnit.SECONDS));
    }

    /** Records {@code count} breaks and packs them all into archive segments of five events. */
    private void archiveInSegmentsOfFive(int count) throws Exception {
        LookupFixtures.breaks(Actors.NATURE, world, count);
        TestPlugins.flush();
        Shard home = TestPlugins.awaitStorage().home();
        Archive archive = TestPlugins.bean(Archive.class);
        int packed = 0;
        int pass;
        while ((pass = archive.compact(home, new ArchiveSettings().withPacking(5, 60, 1), Long.MAX_VALUE / 4)) > 0) {
            packed += pass;
        }
        assertEquals(count, packed);
        assertEquals(count / 5, archive.totals(home).segments());
    }

    private List<StoredEvent> shownEvents(int count) {
        LookupFixtures.breaks(Actors.NATURE, world, count);
        TestPlugins.flush();
        return TestPlugins.bean(LookupService.class).page(EVERYWHERE, 0, count).thenApply(outcome ->
                ((LookupOutcome.Page) outcome).events()).join();
    }

    private static PageView shown(CompletableFuture<PageResult> result) throws Exception {
        return assertInstanceOf(PageResult.Shown.class, result.get(20, TimeUnit.SECONDS)).view();
    }
}
