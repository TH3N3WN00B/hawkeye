package gg.moonrise.hawkeye.core.queue;

import gg.moonrise.hawkeye.config.HawkeyeConfigProvider;
import gg.moonrise.hawkeye.config.file.WriterSettings;
import gg.moonrise.hawkeye.core.scheduler.HawkeyeExecutors;
import gg.moonrise.hawkeye.core.storage.EventStore;
import gg.moonrise.hawkeye.core.storage.StorageManager;
import gg.moonrise.hawkeye.core.storage.StorageState;
import gg.moonrise.hawkeye.entity.event.PendingEvent;
import gg.moonrise.moss.spring.Disableable;
import gg.moonrise.moss.spring.Enableable;
import gg.moonrise.moss.spring.SpringComponent;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

/**
 * The single writer thread. It sleeps until a batch is full, the flush interval passes or someone
 * asks for a flush, then writes everything queued in batches of {@code writer.batch-size}. A failed
 * batch is kept and retried with back-off (the queue keeps accepting events up to its capacity), so
 * a database restart loses nothing. On disable it drains the queue for up to
 * {@code writer.shutdown-timeout-seconds} and spools the rest to disk for the next start.
 *
 * <p>{@link #flush()} gives read-your-writes: lookups call it first, so they see every event captured
 * before the command.
 */
@Slf4j
@SpringComponent
@RequiredArgsConstructor
public final class EventWriter implements Enableable, Disableable {

    private static final long MIN_RETRY_MILLIS = 250;

    private final @NotNull EventQueue queue;
    private final @NotNull StorageManager storage;
    private final @NotNull EventStore store;
    private final @NotNull EventSpool spool;
    private final @NotNull HawkeyeConfigProvider configProvider;

    private final ExecutorService thread = Executors.newSingleThreadExecutor(HawkeyeExecutors.threads("Hawkeye-Writer"));
    private final ConcurrentLinkedQueue<CompletableFuture<Void>> flushRequests = new ConcurrentLinkedQueue<>();
    private final AtomicLong written = new AtomicLong();
    private final AtomicLong batches = new AtomicLong();
    private final AtomicLong spooled = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong lastFlushMillis = new AtomicLong();
    private final AtomicReference<String> lastError = new AtomicReference<>("");
    private final CompletableFuture<Void> stopped = new CompletableFuture<>();
    private volatile boolean running;
    private List<PendingEvent> retry = List.of();
    private int consecutiveFailures;
    private boolean spoolReplayed;

    @Override
    public void onEnable() {
        running = true;
        thread.execute(this::run);
    }

    @Override
    public void onDisable() {
        running = false;
        queue.wake();
        int timeout = configProvider.get().writer().shutdownTimeoutSeconds();
        try {
            stopped.get(timeout + 5L, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("The writer did not stop in time; queued events may be lost", e);
        }
        thread.shutdownNow();
    }

    /**
     * Writes everything queued so far.
     *
     * @return completes when every event queued before the call is written, or the attempt failed
     *         (the events then stay queued and the caller reads what is already stored)
     */
    public @NotNull CompletableFuture<Void> flush() {
        if (!running) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> request = new CompletableFuture<>();
        flushRequests.add(request);
        queue.wake();
        return request;
    }

    /**
     * The writer's counters.
     *
     * @return a snapshot
     */
    public @NotNull WriterStats stats() {
        return new WriterStats(queue.size() + retry.size(), written.get(), batches.get(), queue.dropped(),
                spooled.get(), failures.get(), lastFlushMillis.get(), lastError.get());
    }

    private void run() {
        try {
            while (running) {
                queue.awaitWork(settings().flushIntervalMillis());
                if (!running) {
                    break;
                }
                List<CompletableFuture<Void>> requests = takeRequests();
                writeAvailable();
                requests.forEach(request -> request.complete(null));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.error("The writer loop failed", e);
        } finally {
            shutdownDrain();
            takeRequests().forEach(request -> request.complete(null));
            stopped.complete(null);
        }
    }

    /** Writes the retry batch, then the queue, until it is empty or a batch fails. */
    private void writeAvailable() throws InterruptedException {
        Optional<StorageState.Ready> ready = storage.readyNow();
        if (ready.isEmpty()) {
            return;
        }
        if (!spoolReplayed) {
            spoolReplayed = replaySpool(ready.get());
        }
        while (running) {
            List<PendingEvent> batch = retry.isEmpty() ? queue.drain(settings().batchSize()) : retry;
            if (batch.isEmpty()) {
                return;
            }
            if (!write(ready.get(), batch)) {
                retry = batch;
                backOff();
                return;
            }
            retry = List.of();
        }
    }

    private boolean write(@NotNull StorageState.Ready ready, @NotNull List<PendingEvent> batch) {
        long start = System.nanoTime();
        try {
            store.write(ready.home(), ready.node(), storage.worldScope(), batch);
            written.addAndGet(batch.size());
            batches.incrementAndGet();
            lastFlushMillis.set(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
            consecutiveFailures = 0;
            return true;
        } catch (SQLException | RuntimeException e) {
            failures.incrementAndGet();
            consecutiveFailures++;
            String reason = Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName());
            lastError.set(reason);
            if (consecutiveFailures == 1 || consecutiveFailures % 20 == 0) {
                log.warn("Could not write {} event(s) (attempt {}), keeping them queued: {}", batch.size(),
                        consecutiveFailures, reason);
            }
            return false;
        }
    }

    private void backOff() throws InterruptedException {
        long max = TimeUnit.SECONDS.toMillis(settings().maxRetryDelaySeconds());
        long delay = Math.min(max, MIN_RETRY_MILLIS << Math.min(consecutiveFailures, 10));
        // Anyone waiting on a flush reads what is stored rather than waiting out the outage.
        takeRequests().forEach(request -> request.complete(null));
        queue.awaitWork(delay);
    }

    private boolean replaySpool(@NotNull StorageState.Ready ready) {
        List<Path> files;
        try {
            files = spool.files();
        } catch (IOException e) {
            log.warn("Could not list the spool folder", e);
            return true;
        }
        for (Path file : files) {
            List<PendingEvent> events;
            try {
                events = spool.read(file);
            } catch (IOException | RuntimeException e) {
                log.error("Spool file {} cannot be read; moving it aside", file.getFileName(), e);
                quarantine(file);
                continue;
            }
            int batchSize = settings().batchSize();
            for (int from = 0; from < events.size(); from += batchSize) {
                if (!write(ready, events.subList(from, Math.min(events.size(), from + batchSize)))) {
                    return false;
                }
            }
            try {
                spool.delete(file);
                log.info("Wrote {} spooled event(s) from {}", events.size(), file.getFileName());
            } catch (IOException e) {
                log.warn("Could not delete replayed spool file {}", file.getFileName(), e);
            }
        }
        return true;
    }

    private void quarantine(@NotNull Path file) {
        try {
            spool.quarantine(file);
        } catch (IOException e) {
            log.error("Could not move spool file {} aside", file.getFileName(), e);
        }
    }

    private void shutdownDrain() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(settings().shutdownTimeoutSeconds());
        List<PendingEvent> left = new ArrayList<>(retry);
        retry = List.of();
        Optional<StorageState.Ready> ready = storage.readyNow();
        boolean healthy = ready.isPresent();
        if (healthy && !left.isEmpty()) {
            healthy = write(ready.get(), left);
            if (healthy) {
                left.clear();
            }
        }
        while (healthy && System.nanoTime() < deadline) {
            List<PendingEvent> batch = queue.drain(settings().batchSize());
            if (batch.isEmpty()) {
                break;
            }
            if (!write(ready.get(), batch)) {
                left.addAll(batch);
                healthy = false;
            }
        }
        left.addAll(queue.drain(Integer.MAX_VALUE));
        if (left.isEmpty()) {
            return;
        }
        try {
            Path file = spool.write(left);
            spooled.addAndGet(left.size());
            log.warn("Spooled {} unwritten event(s) to {}; they are written on the next start", left.size(),
                    file.getFileName());
        } catch (IOException e) {
            log.error("Could not spool {} unwritten event(s); they are lost", left.size(), e);
        }
    }

    private @NotNull List<CompletableFuture<Void>> takeRequests() {
        List<CompletableFuture<Void>> requests = new ArrayList<>();
        CompletableFuture<Void> request;
        while ((request = flushRequests.poll()) != null) {
            requests.add(request);
        }
        return requests;
    }

    private @NotNull WriterSettings settings() {
        return configProvider.get().writer();
    }
}
