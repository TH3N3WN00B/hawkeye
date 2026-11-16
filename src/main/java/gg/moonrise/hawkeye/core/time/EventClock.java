package gg.moonrise.hawkeye.core.time;

import gg.moonrise.hawkeye.util.EventIds;
import gg.moonrise.moss.spring.SpringComponent;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

/**
 * Hands out capture stamps ({@code [ms since the Hawkeye epoch][12-bit sequence]}) from any thread
 * without locking. Stamps strictly increase: within one millisecond the sequence counts up, a burst
 * beyond 4,096 events borrows the next millisecond, and a wall clock that steps backwards keeps the
 * last time until it catches up. The writer turns a stamp into an id with the node id
 * ({@link EventIds#fromStamp(long, int)}).
 */
@SpringComponent
@RequiredArgsConstructor
public final class EventClock {

    private final @NotNull Clock clock;
    private final AtomicLong last = new AtomicLong();

    /**
     * The next stamp.
     *
     * @return a stamp larger than every stamp returned before
     */
    public long next() {
        long now = EventIds.stamp(clock.millis(), 0);
        while (true) {
            long previous = last.get();
            long next = now > previous ? now : previous + 1;
            if (last.compareAndSet(previous, next)) {
                return next;
            }
        }
    }

    /**
     * The current wall-clock time.
     *
     * @return Unix milliseconds
     */
    public long millis() {
        return clock.millis();
    }
}
