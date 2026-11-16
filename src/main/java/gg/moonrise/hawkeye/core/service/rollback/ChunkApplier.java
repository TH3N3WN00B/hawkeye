package gg.moonrise.hawkeye.core.service.rollback;

import gg.moonrise.hawkeye.core.service.thread.ServerThreads;
import gg.moonrise.moss.spring.Disableable;
import gg.moonrise.moss.spring.SpringComponent;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

/**
 * Applies a plan chunk by chunk on the region that owns each chunk. At most
 * {@code concurrentChunks} chunk tasks are in flight; each task makes at most {@code changesPerTask}
 * changes and schedules the rest of its chunk as a new task, so a large rollback spreads over many
 * ticks instead of stalling one.
 */
@Slf4j
@SpringComponent
@RequiredArgsConstructor
public final class ChunkApplier implements Disableable {

    private final @NotNull JavaPlugin plugin;
    private final @NotNull ServerThreads threads;
    private final @NotNull WorldWriter writer;
    private final Set<Job> jobs = ConcurrentHashMap.newKeySet();

    /** Hears each finished chunk. */
    @FunctionalInterface
    public interface Progress {

        /**
         * A chunk is done.
         *
         * @param done  chunks done so far
         * @param total chunks in the plan
         */
        void chunkDone(int done, int total);
    }

    /**
     * What applying a plan did.
     *
     * @param blocks   block states and sign texts written
     * @param items    container item changes made
     * @param entities entities spawned or removed
     * @param skipped  changes that did not fit the world any more (or failed)
     */
    public record Result(int blocks, int items, int entities, int skipped) {
    }

    /**
     * Applies a plan.
     *
     * @param plan             the plan
     * @param changesPerTask   the most changes one chunk task makes
     * @param concurrentChunks the most chunk tasks in flight
     * @param progress         hears each finished chunk
     * @return completes with the counts once every chunk is done; fails when scheduling fails or the
     *         plugin shuts down
     */
    public @NotNull CompletableFuture<Result> apply(@NotNull RollbackPlan plan, int changesPerTask,
            int concurrentChunks, @NotNull Progress progress) {
        Job job = new Job(plan.chunks(), Math.max(1, changesPerTask), progress);
        int workers = Math.min(Math.max(1, concurrentChunks), plan.chunks().size());
        if (workers == 0) {
            return CompletableFuture.completedFuture(new Result(0, 0, 0, 0));
        }
        jobs.add(job);
        job.start(workers);
        return job.future;
    }

    /** Fails every running job: the server cancels the plugin's tasks, so they would never finish. */
    @Override
    public void onDisable() {
        jobs.forEach(job -> job.fail(new IllegalStateException("Hawkeye is shutting down")));
    }

    /** One plan being applied. Its counters are touched by several region threads at once on Folia. */
    private final class Job {

        private final Queue<ChunkPlan> pending;
        private final int total;
        private final int changesPerTask;
        private final Progress progress;
        private final CompletableFuture<Result> future = new CompletableFuture<>();
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger done = new AtomicInteger();
        private final AtomicInteger blocks = new AtomicInteger();
        private final AtomicInteger items = new AtomicInteger();
        private final AtomicInteger entities = new AtomicInteger();
        private final AtomicInteger skipped = new AtomicInteger();

        private Job(@NotNull List<ChunkPlan> chunks, int changesPerTask, @NotNull Progress progress) {
            this.pending = new ConcurrentLinkedQueue<>(chunks);
            this.total = chunks.size();
            this.changesPerTask = changesPerTask;
            this.progress = progress;
        }

        private void start(int workers) {
            active.set(workers);
            for (int worker = 0; worker < workers; worker++) {
                next();
            }
        }

        /** Takes the next chunk for a worker, or retires the worker when none is left. */
        private void next() {
            while (!future.isDone()) {
                ChunkPlan chunk = pending.poll();
                if (chunk == null) {
                    if (active.decrementAndGet() == 0) {
                        finish();
                    }
                    return;
                }
                World world = plugin.getServer().getWorld(chunk.world());
                if (world != null) {
                    schedule(world, chunk, 0);
                    return;
                }
                log.debug("World {} is not loaded; skipping {} change(s)", chunk.world(), chunk.changes().size());
                skipped.addAndGet(chunk.changes().size());
                chunkDone();
            }
        }

        private void schedule(@NotNull World world, @NotNull ChunkPlan chunk, int from) {
            try {
                threads.runAtChunk(world, chunk.chunkX(), chunk.chunkZ(), () -> step(world, chunk, from));
            } catch (RuntimeException e) {
                fail(e);
            }
        }

        private void step(@NotNull World world, @NotNull ChunkPlan chunk, int from) {
            if (future.isDone()) {
                return;
            }
            try {
                List<PlannedChange> changes = chunk.changes();
                int to = Math.min(changes.size(), from + changesPerTask);
                for (int index = from; index < to; index++) {
                    applyOne(world, changes.get(index));
                }
                if (to < changes.size()) {
                    schedule(world, chunk, to);
                    return;
                }
                chunkDone();
                next();
            } catch (RuntimeException e) {
                fail(e);
            }
        }

        private void applyOne(@NotNull World world, @NotNull PlannedChange change) {
            try {
                AtomicInteger counter = switch (writer.apply(world, change)) {
                    case BLOCK -> blocks;
                    case ITEMS -> items;
                    case ENTITY -> entities;
                    case NONE -> skipped;
                };
                counter.incrementAndGet();
            } catch (RuntimeException e) {
                skipped.incrementAndGet();
                log.debug("Could not apply {} in {}", change, world.getName(), e);
            }
        }

        private void chunkDone() {
            int finished = done.incrementAndGet();
            try {
                progress.chunkDone(finished, total);
            } catch (RuntimeException e) {
                log.warn("A rollback progress listener failed", e);
            }
        }

        private void finish() {
            jobs.remove(this);
            future.complete(new Result(blocks.get(), items.get(), entities.get(), skipped.get()));
        }

        private void fail(@NotNull Throwable error) {
            jobs.remove(this);
            future.completeExceptionally(error);
        }
    }
}
