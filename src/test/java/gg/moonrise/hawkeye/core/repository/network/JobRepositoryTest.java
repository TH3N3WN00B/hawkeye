package gg.moonrise.hawkeye.core.repository.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.core.sql.SqlWork;
import gg.moonrise.hawkeye.core.sql.SqliteHarness;
import gg.moonrise.hawkeye.entity.job.JobKind;
import gg.moonrise.hawkeye.entity.job.JobStatus;
import gg.moonrise.hawkeye.entity.job.NetworkJob;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class JobRepositoryTest {

    @TempDir
    private Path folder;

    private SqliteHarness harness;
    private final JobRepository jobs = new JobRepository();

    @BeforeEach
    public void setUp() throws SQLException {
        harness = SqliteHarness.open(folder);
    }

    @AfterEach
    public void tearDown() {
        harness.close();
    }

    @Test
    public void aJobIsClaimedExactlyOnce() throws SQLException {
        long id = insert("creative", 1000);
        List<NetworkJob> pending = read(connection -> jobs.pending(connection, harness.database().sql(), "creative"));
        assertEquals(1, pending.size());
        assertEquals(JobKind.ROLLBACK, pending.getFirst().kind());
        boolean first = read(connection -> jobs.claim(connection, harness.database().sql(), id, 1100));
        boolean second = read(connection -> jobs.claim(connection, harness.database().sql(), id, 1200));
        assertTrue(first);
        assertFalse(second);
        assertEquals(List.of(), read(connection -> jobs.pending(connection, harness.database().sql(), "creative")));
    }

    @Test
    public void finishedJobsCarryTheirResultAndArePruned() throws SQLException {
        long id = insert("creative", 1000);
        read(connection -> {
            jobs.finish(connection, harness.database().sql(), id, JobStatus.DONE, "ok|1|0|0|5", 2000);
            return null;
        });
        NetworkJob job = read(connection -> jobs.byIds(connection, harness.database().sql(), List.of(id))).getFirst();
        assertEquals(JobStatus.DONE, job.status());
        assertEquals("ok|1|0|0|5", job.result());
        assertEquals(1, (int) read(connection -> jobs.prune(connection, harness.database().sql(), 3000)));
    }

    @Test
    public void stuckJobsAreAbandoned() throws SQLException {
        long id = insert("creative", 1000);
        read(connection -> jobs.claim(connection, harness.database().sql(), id, 1000));
        assertEquals(0, (int) read(connection -> jobs.abandon(connection, harness.database().sql(), "creative", 500,
                "gone", 5000)));
        assertEquals(1, (int) read(connection -> jobs.abandon(connection, harness.database().sql(), "creative", 2000,
                "gone", 5000)));
        NetworkJob job = read(connection -> jobs.byIds(connection, harness.database().sql(), List.of(id))).getFirst();
        assertEquals(JobStatus.FAILED, job.status());
    }

    private long insert(String target, long now) throws SQLException {
        return read(connection -> jobs.insert(connection, harness.database().sql(), target, JobKind.ROLLBACK,
                "survival", "Alex", "v=1", now));
    }

    private <T> T read(SqlWork<T> work) throws SQLException {
        return harness.database().read(work);
    }
}
