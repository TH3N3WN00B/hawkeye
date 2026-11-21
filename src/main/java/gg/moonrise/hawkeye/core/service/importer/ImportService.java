package gg.moonrise.hawkeye.core.service.importer;

import gg.moonrise.hawkeye.config.HawkeyeConfigProvider;
import gg.moonrise.hawkeye.config.file.HawkeyeConfig;
import gg.moonrise.hawkeye.core.repository.coreprotect.CoreProtectSources;
import gg.moonrise.hawkeye.core.scheduler.HawkeyeExecutors;
import gg.moonrise.hawkeye.core.service.importer.mapping.ServerBlockStates;
import gg.moonrise.hawkeye.core.service.importer.mapping.ServerItemDecoder;
import gg.moonrise.hawkeye.core.service.importer.pipeline.ImportRun;
import gg.moonrise.hawkeye.core.service.importer.pipeline.RunSettings;
import gg.moonrise.hawkeye.core.storage.importer.ImportStore;
import gg.moonrise.hawkeye.core.storage.shard.StorageManager;
import gg.moonrise.hawkeye.core.storage.shard.StorageState;
import gg.moonrise.moss.spring.SpringComponent;
import jakarta.annotation.PreDestroy;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Imports CoreProtect history ({@code /hawkeye import coreprotect}), one import at a time per server.
 * Each run gets its own {@code Hawkeye-Import} thread and a small {@code Hawkeye-Import-Pack} pool that
 * compresses segment frames; both end with the run. A run only adds to Hawkeye's storage and reads the
 * source read-only; a cancelled, failed or interrupted one continues from its last checkpoint the next
 * time ({@link ImportRun}).
 */
@Slf4j
@SpringComponent
@RequiredArgsConstructor
public final class ImportService {

    /** How often a running import reports its progress to the listener. */
    public static final Duration PROGRESS_INTERVAL = Duration.ofSeconds(30);

    /** Rows after which a checkpoint is written even when they filled no segment. */
    public static final long CHECKPOINT_ROWS = 1_000_000;

    /** Rows read from one source table at a time. */
    public static final int PAGE_ROWS = 4_096;

    /** Threads compressing segment frames during an import. */
    public static final int PACK_THREADS = 3;

    /** The plugin whose DuckDB file cannot be opened a second time while it runs. */
    public static final String CORE_PROTECT = "CoreProtect";

    /** How long shutdown waits for a running import to stop at a batch boundary. */
    public static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(10);

    private final @NotNull StorageManager storage;
    private final @NotNull ImportStore store;
    private final @NotNull CoreProtectSources sources;
    private final @NotNull HawkeyeConfigProvider configProvider;
    private final @NotNull JavaPlugin plugin;
    private final @NotNull Clock clock;
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile ImportRun current;
    private volatile CompletableFuture<ImportOutcome> finished;
    private volatile int batchEvents;
    private volatile long progressMillis = PROGRESS_INTERVAL.toMillis();
    private volatile long settleSeconds;

    /**
     * Starts an import, unless one is running or storage is not ready.
     *
     * @param source   a CoreProtect folder, its {@code config.yml} or a database file; {@code null} or blank for
     *                 {@code plugins/CoreProtect}
     * @param listener hears how it goes, on the import thread
     * @return how it ended; never fails exceptionally
     */
    public @NotNull CompletableFuture<ImportOutcome> start(@Nullable String source, @NotNull ImportListener listener) {
        Optional<StorageState.Ready> ready = storage.readyNow();
        if (ready.isEmpty()) {
            return CompletableFuture.completedFuture(new ImportOutcome.NotReady());
        }
        if (!busy.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(new ImportOutcome.AlreadyRunning());
        }
        RunSettings settings;
        try {
            settings = settings(source, plugin.getServer().getPluginManager().isPluginEnabled(CORE_PROTECT));
        } catch (RuntimeException e) {
            busy.set(false);
            log.warn("The CoreProtect import could not start", e);
            return CompletableFuture.completedFuture(new ImportOutcome.Failed(
                    Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName())));
        }
        ExecutorService pack = Executors.newFixedThreadPool(PACK_THREADS,
                HawkeyeExecutors.threads("Hawkeye-Import-Pack"));
        ExecutorService thread = Executors.newSingleThreadExecutor(HawkeyeExecutors.threads("Hawkeye-Import"));
        ImportRun run = new ImportRun(settings, sources, store, ready.get().home(), clock, pack, listener,
                new ServerBlockStates(), new ServerItemDecoder());
        CompletableFuture<ImportOutcome> outcome = new CompletableFuture<>();
        current = run;
        finished = outcome;
        try {
            thread.execute(() -> outcome.complete(run(run, pack, thread)));
        } catch (RejectedExecutionException e) {
            outcome.complete(release(new ImportOutcome.Failed("the import thread could not start"), pack, thread));
        }
        return outcome;
    }

    /**
     * Asks the running import to stop after the batch it is on; what it mapped is written with its
     * checkpoint, and starting the import again continues from there.
     *
     * @return {@code false} when no import is running
     */
    public boolean cancel() {
        ImportRun run = current;
        if (run == null) {
            return false;
        }
        run.cancel();
        return true;
    }

    /**
     * Where the running import stands.
     *
     * @return a snapshot, or empty when none runs (or it has not opened its source yet)
     */
    public @NotNull Optional<ImportProgress> progress() {
        ImportRun run = current;
        return run == null ? Optional.empty() : run.progress();
    }

    /**
     * Whether an import is running.
     *
     * @return {@code true} while one runs
     */
    public boolean running() {
        return busy.get();
    }

    /** Cancels a running import and waits briefly for it to stop at a batch boundary. */
    @PreDestroy
    public void shutdown() {
        CompletableFuture<ImportOutcome> outcome = finished;
        if (!cancel() || outcome == null) {
            return;
        }
        try {
            outcome.get(SHUTDOWN_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("The CoreProtect import did not stop within {} s; it resumes from its last checkpoint next time",
                    SHUTDOWN_WAIT.toSeconds());
        } catch (ExecutionException e) {
            log.warn("The CoreProtect import failed while stopping", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Changes the batch size and progress interval of later runs, so tests need neither the 16,384
     * events of the smallest configurable segment nor 30 seconds.
     *
     * @param events         events per segment, or 0 for {@code archive.segment-events}
     * @param progressMillis the progress interval
     */
    void tune(int events, long progressMillis) {
        this.batchEvents = events;
        this.progressMillis = progressMillis;
    }

    /**
     * Makes later runs stop at least this long before their start, so tests can see a server engine's
     * settle time on a SQLite source.
     *
     * @param seconds the settle time; 0 for the source's own
     */
    void settle(long seconds) {
        this.settleSeconds = seconds;
    }

    private @NotNull ImportOutcome run(@NotNull ImportRun run, @NotNull ExecutorService pack,
            @NotNull ExecutorService thread) {
        ImportOutcome outcome;
        try {
            outcome = run.call();
        } catch (Throwable e) {
            // Anything, an Error included: the outcome must complete and the next import must be able to start.
            log.error("The CoreProtect import failed", e);
            outcome = new ImportOutcome.Failed(Objects.requireNonNullElse(e.getMessage(),
                    e.getClass().getSimpleName()));
        }
        return release(outcome, pack, thread);
    }

    /** Ends the run's threads and lets the next import start, before its outcome is published. */
    private @NotNull ImportOutcome release(@NotNull ImportOutcome outcome, @NotNull ExecutorService pack,
            @NotNull ExecutorService thread) {
        pack.shutdown();
        thread.shutdown();
        current = null;
        busy.set(false);
        return outcome;
    }

    private @NotNull RunSettings settings(@Nullable String source, boolean coreProtect) {
        HawkeyeConfig config = configProvider.get();
        Path plugins = plugin.getDataPath().toAbsolutePath().getParent();
        int events = batchEvents > 0 ? batchEvents : config.archive().segmentEvents();
        return new RunSettings(source, plugins.resolve(CORE_PROTECT), Path.of("").toAbsolutePath(),
                config.server().name(), storage.worldScope(), events, CHECKPOINT_ROWS, PAGE_ROWS, progressMillis,
                coreProtect, clock.millis(), settleSeconds);
    }
}
