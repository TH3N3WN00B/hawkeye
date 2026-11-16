package gg.moonrise.hawkeye.core.repository.place;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.util.codec.ByteReader;
import gg.moonrise.hawkeye.util.codec.ByteWriter;
import java.util.Random;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

public class IdRunsTest {

    @Test
    public void addRemoveAndTrimAgreeWithASortedSet() {
        Random random = new Random(7);
        IdRuns runs = new IdRuns();
        TreeSet<Long> expected = new TreeSet<>();
        for (int i = 0; i < 20_000; i++) {
            long value = random.nextInt(400);
            if (random.nextInt(3) == 0) {
                assertEquals(expected.remove(value), runs.remove(value));
            } else {
                runs.add(value);
                expected.add(value);
            }
            if (i % 5_000 == 4_999) {
                long bound = random.nextInt(100);
                runs.dropBelow(bound);
                expected.headSet(bound).clear();
            }
        }
        assertEquals(expected.size(), runs.count());
        TreeSet<Long> actual = new TreeSet<>();
        runs.forEach(actual::add);
        assertEquals(expected, actual);
        for (int run = 1; run < runs.runCount(); run++) {
            assertTrue(runs.start(run) > runs.end(run - 1) + 1, "runs are never adjacent");
        }
    }

    @Test
    public void roundTripsAndMerges() {
        IdRuns runs = IdRuns.of(3, 4, 5, 9, 20, 21, 1_000_000);
        ByteWriter out = new ByteWriter(16);
        runs.write(out, 7);
        assertEquals(runs, IdRuns.read(new ByteReader(out.toByteArray()), 7));
        assertEquals("[3..5, 9, 20..21, 1000000]", runs.toString());

        IdRuns other = IdRuns.of(1, 6, 10, 22);
        other.addAll(runs);
        assertEquals("[1, 3..6, 9..10, 20..22, 1000000]", other.toString());
        assertTrue(other.contains(10) && !other.contains(8));
    }

    @Test
    public void selectsTheIdsOfSomeBuckets() {
        IdRuns ids = IdRuns.range(1, 70);
        IdRuns buckets = IdRuns.of(1, 3);
        assertEquals("[16..31, 48..63]", ids.inBuckets(buckets, 4).toString());
        assertFalse(IdRuns.of(5).inBuckets(IdRuns.of(1), 4).contains(5));
    }
}
