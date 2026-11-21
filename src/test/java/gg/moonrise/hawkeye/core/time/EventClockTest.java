package gg.moonrise.hawkeye.core.time;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.util.event.EventIds;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

public class EventClockTest {

    private static final long T = EventIds.EPOCH_MILLIS + 1_000_000L;

    @Test
    public void stampsStrictlyIncreaseWithinAMillisecond() {
        EventClock clock = new EventClock(Clock.fixed(Instant.ofEpochMilli(T), ZoneOffset.UTC));
        long first = clock.next();
        long second = clock.next();
        assertEquals(first + 1, second);
        assertEquals(T, EventIds.stampMillis(first));
    }

    @Test
    public void aBurstBorrowsTheNextMillisecond() {
        EventClock clock = new EventClock(Clock.fixed(Instant.ofEpochMilli(T), ZoneOffset.UTC));
        long last = 0;
        for (int i = 0; i <= EventIds.MAX_SEQUENCE + 1; i++) {
            last = clock.next();
        }
        assertEquals(T + 1, EventIds.stampMillis(last));
    }

    @Test
    public void aClockSteppingBackNeverRepeatsAStamp() {
        AtomicLong now = new AtomicLong(T);
        EventClock clock = new EventClock(new SteppingClock(now));
        long before = clock.next();
        now.set(T - 10_000);
        assertTrue(clock.next() > before);
    }

    @Test
    public void concurrentCallersGetUniqueStamps() {
        EventClock clock = new EventClock(Clock.systemUTC());
        Set<Long> stamps = ConcurrentHashMap.newKeySet();
        IntStream.range(0, 8).parallel().forEach(thread -> {
            for (int i = 0; i < 20_000; i++) {
                stamps.add(clock.next());
            }
        });
        assertEquals(160_000, stamps.size());
        assertEquals(new HashSet<>(List.copyOf(stamps)).size(), stamps.size());
    }

    private static final class SteppingClock extends Clock {

        private final AtomicLong now;

        private SteppingClock(AtomicLong now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(now.get());
        }
    }
}
