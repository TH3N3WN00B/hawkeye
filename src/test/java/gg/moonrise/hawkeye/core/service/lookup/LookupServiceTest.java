package gg.moonrise.hawkeye.core.service.lookup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.core.scheduler.HawkeyeExecutors;
import gg.moonrise.hawkeye.core.service.capture.Actors;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.entity.query.Area;
import gg.moonrise.hawkeye.entity.query.LookupQuery;
import gg.moonrise.hawkeye.entity.query.RollbackState;
import gg.moonrise.hawkeye.entity.query.ServerScope;
import gg.moonrise.hawkeye.entity.query.TimeWindow;
import gg.moonrise.hawkeye.support.TestHawkeyePlugin;
import gg.moonrise.hawkeye.support.TestPlugins;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/** Lookups against the plugin's SQLite storage on a mocked server. */
public class LookupServiceTest {

    private static final LookupQuery EVERYWHERE = LookupQuery.at(new Area.Everywhere());

    /** The threads of {@code HawkeyeExecutors}' query pool. */
    private static final int QUERY_THREADS = 4;

    private ServerMock server;
    private TestHawkeyePlugin plugin;

    @BeforeEach
    public void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void pagesWalkEveryEventNewestFirst() throws Exception {
        LookupService lookups = start();
        World world = server.addSimpleWorld("world");
        LookupFixtures.breaks(Actors.NATURE, world, 20);
        TestPlugins.flush();

        LookupOutcome.Page first = page(lookups, 0);
        LookupOutcome.Page second = page(lookups, first.nextCursor());
        LookupOutcome.Page third = page(lookups, second.nextCursor());

        assertEquals(List.of(8, 8, 4), List.of(first.events().size(), second.events().size(), third.events().size()));
        assertEquals(List.of(true, true, false), List.of(first.hasMore(), second.hasMore(), third.hasMore()));
        assertEquals(19, first.events().getFirst().x());
        assertEquals(0, third.events().getLast().x());
        List<StoredEvent> all = new ArrayList<>(first.events());
        all.addAll(second.events());
        all.addAll(third.events());
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < all.size(); i++) {
            assertTrue(ids.add(all.get(i).id()), "a page repeated an event");
            if (i > 0) {
                assertTrue(all.get(i).id() < all.get(i - 1).id(), "events are not newest first");
            }
        }
    }

    @Test
    public void aCountMatchesTheFilter() throws Exception {
        LookupService lookups = start();
        World world = server.addSimpleWorld("world");
        LookupFixtures.breaks(Actors.NATURE, world, 12);
        LookupFixtures.breaks(Actors.FIRE, world, 5);
        TestPlugins.flush();

        LookupQuery fire = new LookupQuery(new ServerScope.Local(), new Area.Everywhere(), TimeWindow.ALL,
                Set.of("#fire"), Set.of(), Set.of(), Set.of(), Set.of(), RollbackState.ANY);
        assertEquals(new LookupOutcome.Count(5, List.of()), lookups.count(fire).get(20, TimeUnit.SECONDS));
        assertEquals(new LookupOutcome.Count(17, List.of()), lookups.count(EVERYWHERE).get(20, TimeUnit.SECONDS));
    }

    @Test
    public void aLookupSeesEventsStillQueued() throws Exception {
        LookupService lookups = start();
        World world = server.addSimpleWorld("world");
        LookupFixtures.breaks(Actors.NATURE, world, 3);

        assertEquals(3, page(lookups, 0).events().size());
    }

    @Test
    public void nothingMatchingIsAnEmptyPage() throws Exception {
        LookupService lookups = start();
        server.addSimpleWorld("world");

        LookupOutcome.Page page = page(lookups, 0);
        assertTrue(page.events().isEmpty());
        assertFalse(page.hasMore());
    }

    @Test
    public void aDatabaseThatIsNotReadyIsReportedAtOnce() throws Exception {
        LookupFixtures.loadWith(server, """
                storage:
                  type: MYSQL
                  host: 127.0.0.1
                  port: 1
                  connection-timeout-millis: 250
                """);
        LookupService lookups = TestPlugins.bean(LookupService.class);

        assertInstanceOf(LookupOutcome.NotReady.class, lookups.page(EVERYWHERE, 0, 8).get(5, TimeUnit.SECONDS));
        assertInstanceOf(LookupOutcome.NotReady.class, lookups.count(EVERYWHERE).get(5, TimeUnit.SECONDS));
    }

    @Test
    public void anUnreadableShardIsAWarningBesideTheOthersResults() throws Exception {
        // A remote shard is never migrated, so an empty SQLite file has no tables to read.
        LookupFixtures.loadWith(server, """
                network:
                  shards:
                    - name: east
                      storage:
                        type: SQLITE
                        sqlite-file: east.db
                """);
        TestPlugins.awaitStorage();
        LookupService lookups = TestPlugins.bean(LookupService.class);
        World world = server.addSimpleWorld("world");
        LookupFixtures.breaks(Actors.NATURE, world, 3);
        TestPlugins.flush();
        LookupQuery network = new LookupQuery(new ServerScope.Network(), new Area.Everywhere(), TimeWindow.ALL,
                Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), RollbackState.ANY);

        LookupOutcome.Page page = assertInstanceOf(LookupOutcome.Page.class,
                lookups.page(network, 0, 8).get(20, TimeUnit.SECONDS));
        assertEquals(3, page.events().size());
        assertEquals(1, page.warnings().size(), page.warnings().toString());
        assertEquals("east", page.warnings().getFirst().shard());

        LookupOutcome.Count count = assertInstanceOf(LookupOutcome.Count.class,
                lookups.count(network).get(20, TimeUnit.SECONDS));
        assertEquals(3, count.total());
        assertEquals("east", count.warnings().getFirst().shard());
    }

    @Test
    public void aLookupStuckBehindOthersTimesOut() throws Exception {
        LookupService lookups = start();
        server.addSimpleWorld("world");
        TestPlugins.writeConfig(plugin, "lookup:\n  timeout-seconds: 1\n");
        TestPlugins.reload();
        CountDownLatch release = new CountDownLatch(1);
        try {
            occupy(release, QUERY_THREADS);
            assertInstanceOf(LookupOutcome.TimedOut.class, lookups.page(EVERYWHERE, 0, 8).get(10, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
        assertInstanceOf(LookupOutcome.Page.class, lookups.page(EVERYWHERE, 0, 8).get(20, TimeUnit.SECONDS));
    }

    @Test
    public void aFullQueryPoolRefusesTheLookup() throws Exception {
        LookupService lookups = start();
        server.addSimpleWorld("world");
        CountDownLatch release = new CountDownLatch(1);
        try {
            occupy(release, QUERY_THREADS);
            ExecutorService query = TestPlugins.bean(HawkeyeExecutors.class).query();
            assertThrows(RejectedExecutionException.class, () -> {
                for (int i = 0; i < 10_000; i++) {
                    query.execute(() -> await(release));
                }
            });
            assertEquals(new LookupOutcome.Failed(LookupService.BUSY), lookups.count(EVERYWHERE).get(10,
                    TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
    }

    /** Occupies {@code threads} query threads with tasks that wait for {@code release}. */
    private static void occupy(CountDownLatch release, int threads) throws InterruptedException {
        ExecutorService query = TestPlugins.bean(HawkeyeExecutors.class).query();
        CountDownLatch running = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            query.execute(() -> {
                running.countDown();
                await(release);
            });
        }
        assertTrue(running.await(10, TimeUnit.SECONDS), "the query threads did not start");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private LookupService start() {
        plugin = TestPlugins.load();
        TestPlugins.awaitStorage();
        return TestPlugins.bean(LookupService.class);
    }

    private static LookupOutcome.Page page(LookupService lookups, long cursor) throws Exception {
        return assertInstanceOf(LookupOutcome.Page.class, lookups.page(EVERYWHERE, cursor, 8).get(20,
                TimeUnit.SECONDS));
    }
}
