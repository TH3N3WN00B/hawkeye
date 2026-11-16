package gg.moonrise.hawkeye.core.service.purge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import gg.moonrise.hawkeye.entity.query.Area;
import gg.moonrise.hawkeye.support.TestHawkeyePlugin;
import gg.moonrise.hawkeye.support.TestPlugins;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

public class PurgeServiceTest {

    private static final Duration MONTH = Duration.ofDays(30);

    private TestHawkeyePlugin plugin;
    private PurgeService purge;

    @BeforeEach
    public void setUp() {
        MockBukkit.mock();
        plugin = TestPlugins.load();
        TestPlugins.awaitStorage();
        purge = TestPlugins.bean(PurgeService.class);
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void onlyEventsOlderThanTheAgeAreDeleted() throws Exception {
        PurgeEvents.write(Duration.ofDays(40), "world", 5);
        PurgeEvents.write(Duration.ofDays(10), "world", 3);

        PurgeOutcome.Purged purged = assertInstanceOf(PurgeOutcome.Purged.class, run(new PurgeRequest(MONTH, Set.of(),
                false)));

        assertEquals(5, purged.events());
        assertEquals(3, PurgeEvents.count(new Area.Everywhere()));
    }

    @Test
    public void largePurgesRunInBatches() throws Exception {
        TestPlugins.writeConfig(plugin, "purge:\n  batch-size: 100\n");
        TestPlugins.reload();
        PurgeEvents.write(Duration.ofDays(40), "world", 250);

        PurgeOutcome.Purged purged = assertInstanceOf(PurgeOutcome.Purged.class, run(new PurgeRequest(MONTH, Set.of(),
                false)));

        assertEquals(250, purged.events());
        assertEquals(0, PurgeEvents.count(new Area.Everywhere()));
    }

    @Test
    public void aPurgeCanBeLimitedToSomeWorlds() throws Exception {
        PurgeEvents.write(Duration.ofDays(40), "world", 4);
        PurgeEvents.write(Duration.ofDays(40), "nether", 2);

        assertEquals(0, assertInstanceOf(PurgeOutcome.Purged.class, run(new PurgeRequest(MONTH, Set.of("unknown"),
                false))).events());
        PurgeOutcome.Purged purged = assertInstanceOf(PurgeOutcome.Purged.class, run(new PurgeRequest(MONTH,
                Set.of("nether"), false)));

        assertEquals(2, purged.events());
        assertEquals(4, PurgeEvents.count(new Area.World("world")));
        assertEquals(0, PurgeEvents.count(new Area.World("nether")));
    }

    @Test
    public void playersOnlyPurgeDataOlderThanTheMinimum() throws Exception {
        PurgeEvents.write(Duration.ofDays(5), "world", 2);

        assertEquals(new PurgeOutcome.TooRecent(30), run(new PurgeRequest(Duration.ofDays(1), Set.of(), true)));
        assertEquals(2, PurgeEvents.count(new Area.Everywhere()));
        PurgeOutcome.Purged console = assertInstanceOf(PurgeOutcome.Purged.class,
                run(new PurgeRequest(Duration.ofDays(1), Set.of(), false)));
        assertEquals(2, console.events());
    }

    @Test
    public void onePurgeRunsAtATime() throws Exception {
        AtomicReference<PurgeOutcome> second = new AtomicReference<>();
        PurgeOutcome first = purge.purge(new PurgeRequest(MONTH, Set.of(), false),
                () -> second.set(purge.purge(new PurgeRequest(MONTH, Set.of(), false)).join()))
                .get(20, TimeUnit.SECONDS);

        assertInstanceOf(PurgeOutcome.Purged.class, first);
        assertEquals(new PurgeOutcome.AlreadyRunning(), second.get());
        assertInstanceOf(PurgeOutcome.Purged.class, run(new PurgeRequest(MONTH, Set.of(), false)));
    }

    private PurgeOutcome run(PurgeRequest request) throws Exception {
        return purge.purge(request).get(20, TimeUnit.SECONDS);
    }
}
