package gg.moonrise.hawkeye.core.service.rollback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.support.TestPlugins;
import gg.moonrise.hawkeye.util.BlockPositions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.bukkit.Material;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/** Applying a plan over ticks, chunk by chunk, on the region scheduler. */
public class ChunkApplierTest {

    private ServerMock server;
    private World world;
    private ChunkApplier applier;

    @BeforeEach
    public void setUp() {
        server = MockBukkit.mock();
        TestPlugins.load();
        world = server.addSimpleWorld("world");
        applier = TestPlugins.bean(ChunkApplier.class);
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void aChunkIsSpreadOverTicksByTheTaskLimit() throws Exception {
        RollbackPlan plan = plan(new ChunkPlan("world", 0, 0, List.of(stone(1), stone(2), stone(3))));

        CompletableFuture<ChunkApplier.Result> result = applier.apply(plan, 1, 4, (done, total) -> {
        });

        assertEquals(0, stones(1, 2, 3));
        server.getScheduler().performOneTick();
        assertEquals(1, stones(1, 2, 3));
        server.getScheduler().performOneTick();
        assertEquals(2, stones(1, 2, 3));
        assertFalse(result.isDone());
        server.getScheduler().performOneTick();
        assertEquals(3, stones(1, 2, 3));
        assertEquals(new ChunkApplier.Result(3, 0, 0, 0), result.get(1, TimeUnit.SECONDS));
    }

    @Test
    public void onlyTheConfiguredNumberOfChunksIsInFlight() throws Exception {
        RollbackPlan plan = plan(
                new ChunkPlan("world", 0, 0, List.of(stone(1))),
                new ChunkPlan("world", 1, 0, List.of(stone(17))),
                new ChunkPlan("world", 2, 0, List.of(stone(33))));
        List<String> progress = new CopyOnWriteArrayList<>();

        CompletableFuture<ChunkApplier.Result> result = applier.apply(plan, 100, 1,
                (done, total) -> progress.add(done + "/" + total));

        server.getScheduler().performOneTick();
        assertEquals(1, stones(1, 17, 33));
        server.getScheduler().performOneTick();
        server.getScheduler().performOneTick();
        assertEquals(3, stones(1, 17, 33));
        assertEquals(List.of("1/3", "2/3", "3/3"), progress);
        assertEquals(new ChunkApplier.Result(3, 0, 0, 0), result.get(1, TimeUnit.SECONDS));
    }

    @Test
    public void chunksOfAMissingWorldAreSkipped() throws Exception {
        RollbackPlan plan = plan(new ChunkPlan("nowhere", 0, 0, List.of(stone(1), stone(2))));

        CompletableFuture<ChunkApplier.Result> result = applier.apply(plan, 10, 2, (done, total) -> {
        });

        assertTrue(result.isDone());
        assertEquals(new ChunkApplier.Result(0, 0, 0, 2), result.get());
    }

    @Test
    public void anEmptyPlanIsDoneAtOnce() throws Exception {
        assertEquals(new ChunkApplier.Result(0, 0, 0, 0), applier.apply(plan(), 10, 2, (done, total) -> {
        }).get());
    }

    private static RollbackPlan plan(ChunkPlan... chunks) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < chunks.length; i++) {
            ids.add((long) i + 1);
        }
        return new RollbackPlan(RollbackMode.ROLLBACK, List.of(chunks), ids);
    }

    private static PlannedChange stone(int x) {
        return new PlannedChange.SetBlock(BlockPositions.pack(x, 64, 0), "minecraft:stone", SignPatch.NONE, List.of());
    }

    private int stones(int... xs) {
        int count = 0;
        for (int x : xs) {
            if (world.getBlockAt(x, 64, 0).getType() == Material.STONE) {
                count++;
            }
        }
        return count;
    }
}
