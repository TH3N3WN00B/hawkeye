package gg.moonrise.hawkeye.core.queue.event;

import gg.moonrise.hawkeye.config.provider.HawkeyeConfigProvider;
import gg.moonrise.hawkeye.entity.event.PendingEvent;
import gg.moonrise.moss.spring.SpringComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

/**
 * The hand-off between the game threads and the writer. {@link #offer} is lock-free and never blocks:
 * when the queue holds {@code writer.queue-capacity} events (the database has been down for a long
 * time) new events are dropped and counted. The writer sleeps in {@link #awaitWork} until a full
 * batch is waiting, the flush interval passes or {@link #wake()} is called.
 */
@SpringComponent
@RequiredArgsConstructor
public final class EventQueue {

    private final @NotNull HawkeyeConfigProvider configProvider;
    private final ConcurrentLinkedQueue<PendingEvent> events = new ConcurrentLinkedQueue<>();
    private final AtomicInteger size = new AtomicInteger();
    private final LongAdder accepted = new LongAdder();
    private final LongAdder dropped = new LongAdder();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition work = lock.newCondition();
    private volatile boolean woken;

    /**
     * Queues an event for writing.
     *
     * @param event the event
     * @return {@code false} when the queue is full and the event was dropped
     */
    public boolean offer(@NotNull PendingEvent event) {
        int capacity = configProvider.get().writer().queueCapacity();
        int queued = size.incrementAndGet();
        if (queued > capacity) {
            size.decrementAndGet();
            dropped.increment();
            return false;
        }
        events.add(event);
        accepted.increment();
        if (queued % configProvider.get().writer().batchSize() == 0) {
            wake();
        }
        return true;
    }

    /**
     * Takes up to {@code max} events, oldest first.
     *
     * @param max the most events
     * @return the events
     */
    public @NotNull List<PendingEvent> drain(int max) {
        List<PendingEvent> batch = new ArrayList<>(Math.min(max, Math.max(16, size.get())));
        while (batch.size() < max) {
            PendingEvent event = events.poll();
            if (event == null) {
                break;
            }
            size.decrementAndGet();
            batch.add(event);
        }
        return batch;
    }

    /**
     * Waits until a full batch is queued, {@link #wake()} is called or the timeout passes.
     *
     * @param timeoutMillis the longest wait
     * @throws InterruptedException when the writer thread is interrupted
     */
    public void awaitWork(long timeoutMillis) throws InterruptedException {
        long remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        lock.lock();
        try {
            while (!woken && size.get() < configProvider.get().writer().batchSize() && remaining > 0) {
                remaining = work.awaitNanos(remaining);
            }
            woken = false;
        } finally {
            lock.unlock();
        }
    }

    /** Wakes the writer now (a flush request, a full batch, shutdown). */
    public void wake() {
        lock.lock();
        try {
            woken = true;
            work.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Events waiting to be written.
     *
     * @return the queue length
     */
    public int size() {
        return size.get();
    }

    /**
     * Events accepted since start.
     *
     * @return the count
     */
    public long accepted() {
        return accepted.sum();
    }

    /**
     * Events dropped because the queue was full.
     *
     * @return the count
     */
    public long dropped() {
        return dropped.sum();
    }
}
