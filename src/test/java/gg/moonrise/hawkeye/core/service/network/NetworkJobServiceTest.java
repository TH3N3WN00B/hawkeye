package gg.moonrise.hawkeye.core.service.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.core.repository.network.JobRepository;
import gg.moonrise.hawkeye.core.repository.network.ServerRepository;
import gg.moonrise.hawkeye.core.storage.shard.Shard;
import gg.moonrise.hawkeye.core.storage.shard.StorageState;
import gg.moonrise.hawkeye.entity.job.JobKind;
import gg.moonrise.hawkeye.entity.job.JobResult;
import gg.moonrise.hawkeye.entity.job.JobStatus;
import gg.moonrise.hawkeye.entity.job.NetworkJob;
import gg.moonrise.hawkeye.entity.query.Area;
import gg.moonrise.hawkeye.entity.query.LookupQuery;
import gg.moonrise.hawkeye.entity.query.RollbackState;
import gg.moonrise.hawkeye.entity.query.ServerScope;
import gg.moonrise.hawkeye.entity.query.TimeWindow;
import gg.moonrise.hawkeye.support.TestPlugins;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

/** Dispatch, claim, apply and report across two servers sharing the plugin's SQLite database. */
public class NetworkJobServiceTest {

    private StorageState.Ready ready;
    private NetworkJobService service;
    private final Map<String, JobResult> results = new ConcurrentHashMap<>();

    @BeforeEach
    public void setUp() throws Exception {
        MockBukkit.mock();
        TestPlugins.load();
        ready = TestPlugins.awaitStorage();
        service = TestPlugins.bean(NetworkJobService.class);
        Shard home = ready.home();
        home.database().read(connection -> new ServerRepository().register(connection, home.database().sql(),
                "creative", "creative", System.currentTimeMillis()));
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void anotherServersJobIsWrittenAndItsResultReported() throws Exception {
        DispatchOutcome outcome = service.dispatch(JobKind.ROLLBACK, query(new ServerScope.Named(Set.of("creative"))),
                "Alex", (server, result) -> results.put(server, result)).get(10, TimeUnit.SECONDS);
        assertEquals(new DispatchOutcome.Dispatched(List.of("creative")), outcome);

        Shard home = ready.home();
        JobRepository jobs = TestPlugins.bean(JobRepository.class);
        List<NetworkJob> pending = home.database().read(connection -> jobs.pending(connection, home.database().sql(),
                "creative"));
        assertEquals(1, pending.size());
        NetworkJob job = pending.getFirst();
        assertEquals("server", job.issuerServer());
        // "creative" applies it and records the result.
        home.database().read(connection -> {
            jobs.claim(connection, home.database().sql(), job.id(), System.currentTimeMillis());
            jobs.finish(connection, home.database().sql(), job.id(), JobStatus.DONE,
                    new JobResult.Applied(7, 1, 0, 30).encode(), System.currentTimeMillis());
            return null;
        });
        service.pollSoon();
        awaitResult("creative");
        assertEquals(new JobResult.Applied(7, 1, 0, 30), results.get("creative"));
        assertEquals(0, service.watchedJobs());
    }

    @Test
    public void thisServerAppliesItsOwnJobs() throws Exception {
        DispatchOutcome outcome = service.dispatch(JobKind.RESTORE, query(new ServerScope.Named(Set.of("server"))),
                "Alex", (server, result) -> results.put(server, result)).get(10, TimeUnit.SECONDS);
        assertInstanceOf(DispatchOutcome.Dispatched.class, outcome);
        service.pollSoon();
        awaitResult("server");
        assertTrue(results.get("server") instanceof JobResult, results.toString());
    }

    @Test
    public void anUnknownServerHasNoJob() throws Exception {
        assertEquals(new DispatchOutcome.NoServers(), service.dispatch(JobKind.ROLLBACK,
                query(new ServerScope.Named(Set.of("nowhere"))), "Alex", (server, result) -> { })
                .get(10, TimeUnit.SECONDS));
    }

    private void awaitResult(String server) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!results.containsKey(server) && System.nanoTime() < deadline) {
            service.pollSoon();
            Thread.sleep(50);
        }
        assertTrue(results.containsKey(server), "no result from " + server);
    }

    private static LookupQuery query(ServerScope scope) {
        return new LookupQuery(scope, new Area.Everywhere(), new TimeWindow(0, System.currentTimeMillis()),
                Set.of("alex"), Set.of(), Set.of(), Set.of(), Set.of(), RollbackState.ANY);
    }
}
