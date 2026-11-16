package gg.moonrise.hawkeye.core.service.lookup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.core.service.capture.Actors;
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
        assertEquals(20, view.total());
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
    public void pagingWithoutALookupHasNoSession() throws Exception {
        assertInstanceOf(PageResult.NoSession.class, pager.page(console, 2).get(20, TimeUnit.SECONDS));
    }

    @Test
    public void anEmptyLookupStillStartsASession() throws Exception {
        PageView view = shown(pager.open(console, EVERYWHERE, 8));
        assertTrue(view.events().isEmpty());
        assertEquals(new PageResult.OutOfRange(1), pager.page(console, 2).get(20, TimeUnit.SECONDS));
    }

    private static PageView shown(CompletableFuture<PageResult> result) throws Exception {
        return assertInstanceOf(PageResult.Shown.class, result.get(20, TimeUnit.SECONDS)).view();
    }
}
