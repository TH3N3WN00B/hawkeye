package gg.moonrise.hawkeye.core.storage.index.place;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.util.codec.binary.ByteReader;
import gg.moonrise.hawkeye.util.codec.binary.ByteWriter;
import gg.moonrise.hawkeye.util.codec.binary.CodecException;
import java.util.Arrays;
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

    @Test
    public void intersectsRunByRun() {
        assertEquals(new IdRuns(), new IdRuns().intersect(IdRuns.range(1, 9)), "empty");
        assertEquals(new IdRuns(), IdRuns.range(1, 9).intersect(new IdRuns()), "empty other");
        assertEquals(new IdRuns(), IdRuns.range(1, 4).intersect(IdRuns.range(6, 9)), "disjoint");
        assertEquals(new IdRuns(), IdRuns.of(1, 3, 5).intersect(IdRuns.of(2, 4, 6)), "interleaved, disjoint");
        assertEquals(IdRuns.range(3, 5), IdRuns.range(1, 9).intersect(IdRuns.range(3, 5)), "nested");
        assertEquals(IdRuns.range(3, 5), IdRuns.range(3, 5).intersect(IdRuns.range(1, 9)), "nested, other way");
        assertEquals(IdRuns.of(4), IdRuns.range(1, 4).intersect(IdRuns.range(4, 9)), "runs that touch in one id");
        assertEquals(new IdRuns(), IdRuns.range(1, 4).intersect(IdRuns.range(5, 9)), "adjacent runs share nothing");
        assertEquals("[2..3, 7, 9..10, 40]", IdRuns.of(1, 2, 3, 7, 8, 9, 10, 40)
                .intersect(IdRuns.of(2, 3, 4, 5, 6, 7, 9, 10, 11, 40, 41)).toString(), "several runs");

        Random random = new Random(19);
        for (int round = 0; round < 200; round++) {
            IdRuns a = new IdRuns();
            IdRuns b = new IdRuns();
            TreeSet<Long> expected = new TreeSet<>();
            TreeSet<Long> inB = new TreeSet<>();
            for (int i = 0; i < 60; i++) {
                long x = random.nextInt(120);
                long y = random.nextInt(120);
                a.add(x);
                b.add(y);
                expected.add(x);
                inB.add(y);
            }
            expected.retainAll(inB);
            TreeSet<Long> actual = new TreeSet<>();
            a.intersect(b).forEach(actual::add);
            assertEquals(expected, actual);
        }
    }

    @Test
    public void standsAloneAsBytes() {
        IdRuns runs = IdRuns.of(2, 3, 4, 70, 5_000);
        assertEquals(runs, IdRuns.fromBytes(runs.toBytes()));
        assertEquals(new IdRuns(), IdRuns.fromBytes(new IdRuns().toBytes()));
        assertEquals(2, runs.min());
        byte[] longer = Arrays.copyOf(runs.toBytes(), runs.toBytes().length + 1);
        assertThrows(CodecException.class, () -> IdRuns.fromBytes(longer));
    }
}
