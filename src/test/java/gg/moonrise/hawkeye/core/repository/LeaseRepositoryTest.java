package gg.moonrise.hawkeye.core.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.core.sql.Database;
import gg.moonrise.hawkeye.core.sql.SqliteHarness;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class LeaseRepositoryTest {

    private static final String PURGE = "auto-purge";

    @TempDir
    private Path folder;

    private SqliteHarness harness;
    private Database database;
    private final LeaseRepository leases = new LeaseRepository();

    @BeforeEach
    public void setUp() throws SQLException {
        harness = SqliteHarness.open(folder);
        database = harness.database();
    }

    @AfterEach
    public void tearDown() {
        harness.close();
    }

    @Test
    public void theFirstServerTakesAFreeLease() throws SQLException {
        assertTrue(leases.tryAcquire(database, PURGE, "survival", 1_000, 100));
        assertEquals(Optional.of("survival"), holder(1_050));
    }

    @Test
    public void anotherServerCannotTakeAHeldLease() throws SQLException {
        assertTrue(leases.tryAcquire(database, PURGE, "survival", 1_000, 100));
        assertFalse(leases.tryAcquire(database, PURGE, "creative", 1_050, 100));
        assertEquals(Optional.of("survival"), holder(1_050));
    }

    @Test
    public void theHolderRenewsItsLease() throws SQLException {
        assertTrue(leases.tryAcquire(database, PURGE, "survival", 1_000, 100));
        assertTrue(leases.tryAcquire(database, PURGE, "survival", 1_080, 100));
        assertFalse(leases.tryAcquire(database, PURGE, "creative", 1_150, 100), "renewed until 1180");
        assertEquals(Optional.of("survival"), holder(1_179));
    }

    @Test
    public void anExpiredLeaseIsTakenOver() throws SQLException {
        assertTrue(leases.tryAcquire(database, PURGE, "survival", 1_000, 100));
        assertEquals(Optional.empty(), holder(1_100));
        assertTrue(leases.tryAcquire(database, PURGE, "creative", 1_100, 100));
        assertEquals(Optional.of("creative"), holder(1_150));
        assertFalse(leases.tryAcquire(database, PURGE, "survival", 1_150, 100));
    }

    @Test
    public void onlyTheHolderReleasesALease() throws SQLException {
        assertTrue(leases.tryAcquire(database, PURGE, "survival", 1_000, 100));
        assertFalse(leases.release(database, PURGE, "creative"));
        assertEquals(Optional.of("survival"), holder(1_010));
        assertTrue(leases.release(database, PURGE, "survival"));
        assertEquals(Optional.empty(), holder(1_010));
        assertTrue(leases.tryAcquire(database, PURGE, "creative", 1_020, 100));
    }

    @Test
    public void leasesWithDifferentNamesAreIndependent() throws SQLException {
        assertTrue(leases.tryAcquire(database, PURGE, "survival", 1_000, 100));
        assertTrue(leases.tryAcquire(database, "other", "creative", 1_000, 100));
    }

    @Test
    public void racingServersAgreeOnOneHolder() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int server = 0; server < 8; server++) {
                String name = "server-" + server;
                Callable<Boolean> attempt = () -> leases.tryAcquire(database, PURGE, name, 5_000, 1_000);
                results.add(pool.submit(attempt));
            }
            int holders = 0;
            for (Future<Boolean> result : results) {
                holders += result.get() ? 1 : 0;
            }
            assertEquals(1, holders);
        } finally {
            pool.shutdownNow();
        }
    }

    private Optional<String> holder(long now) throws SQLException {
        return database.read(connection -> leases.holder(connection, database.sql(), PURGE, now));
    }
}
