package gg.moonrise.hawkeye.core.service.purge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.core.repository.LeaseRepository;
import gg.moonrise.hawkeye.core.sql.Database;
import gg.moonrise.hawkeye.entity.query.Area;
import gg.moonrise.hawkeye.support.TestHawkeyePlugin;
import gg.moonrise.hawkeye.support.TestPlugins;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

public class AutoPurgeTest {

    private TestHawkeyePlugin plugin;
    private AutoPurge autoPurge;
    private LeaseRepository leases;
    private Database database;

    @BeforeEach
    public void setUp() {
        MockBukkit.mock();
        plugin = TestPlugins.load();
        database = TestPlugins.awaitStorage().home().database();
        autoPurge = TestPlugins.bean(AutoPurge.class);
        leases = TestPlugins.bean(LeaseRepository.class);
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void itIsOffUntilARetentionIsSet() throws Exception {
        PurgeEvents.write(Duration.ofDays(400), "world", 3);

        assertEquals(new AutoPurgeResult.Disabled(), autoPurge.runOnce().get(20, TimeUnit.SECONDS));
        assertEquals(3, PurgeEvents.count(new Area.Everywhere()));
        assertEquals(Optional.empty(), holder());
    }

    @Test
    public void theLeaseHolderPurgesOldEvents() throws Exception {
        retention(30);
        PurgeEvents.write(Duration.ofDays(40), "world", 3);
        PurgeEvents.write(Duration.ofDays(2), "world", 2);

        AutoPurgeResult.Ran ran = assertInstanceOf(AutoPurgeResult.Ran.class,
                autoPurge.runOnce().get(20, TimeUnit.SECONDS));

        assertEquals(3, assertInstanceOf(PurgeOutcome.Purged.class, ran.outcome()).events());
        assertEquals(2, PurgeEvents.count(new Area.Everywhere()));
        assertTrue(autoPurge.holdsLease());
        assertEquals(Optional.of("server"), holder());
        assertInstanceOf(AutoPurgeResult.Ran.class, autoPurge.runOnce().get(20, TimeUnit.SECONDS), "renews its lease");
    }

    @Test
    public void anotherServersLeaseLeavesThePurgeToIt() throws Exception {
        retention(30);
        PurgeEvents.write(Duration.ofDays(40), "world", 3);
        assertTrue(leases.tryAcquire(database, AutoPurge.LEASE, "creative", System.currentTimeMillis(),
                Duration.ofHours(1).toMillis()));

        assertEquals(new AutoPurgeResult.NotLeaseHolder(), autoPurge.runOnce().get(20, TimeUnit.SECONDS));

        assertFalse(autoPurge.holdsLease());
        assertEquals(3, PurgeEvents.count(new Area.Everywhere()));
    }

    @Test
    public void shuttingDownGivesTheLeaseUp() throws Exception {
        retention(30);
        assertInstanceOf(AutoPurgeResult.Ran.class, autoPurge.runOnce().get(20, TimeUnit.SECONDS));

        autoPurge.onDisable();

        assertFalse(autoPurge.holdsLease());
        assertEquals(Optional.empty(), holder());
    }

    private void retention(int days) {
        TestPlugins.writeConfig(plugin, "purge:\n  retention-days: " + days + "\n");
        TestPlugins.reload();
    }

    private Optional<String> holder() throws SQLException {
        return database.read(connection -> leases.holder(connection, database.sql(), AutoPurge.LEASE,
                System.currentTimeMillis()));
    }
}
