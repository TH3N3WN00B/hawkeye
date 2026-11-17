package gg.moonrise.hawkeye.core.repository.place;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;
import org.junit.jupiter.api.Test;

public class IdUnionTest {

    @Test
    public void nothingAddedIsEmpty() {
        assertEquals(new IdRuns(), new IdUnion().toRuns());
    }

    @Test
    public void overlappingAndTouchingRunsBecomeOne() {
        IdUnion union = new IdUnion();
        union.addAll(IdRuns.range(5, 9));
        union.addAll(IdRuns.of(0, 10, 11, 40));
        union.addAll(IdRuns.range(7, 12));
        assertEquals("[0, 5..12, 40]", union.toRuns().toString());
    }

    @Test
    public void valuesBeyondTheBitSetAreKeptAndJoinTheOnesBelow() {
        IdUnion union = new IdUnion();
        union.addAll(IdRuns.range(IdUnion.DIRECT - 2, IdUnion.DIRECT + 3));
        union.addAll(IdRuns.of(IdUnion.DIRECT + 10, 1L << 40));
        IdRuns expected = IdRuns.range(IdUnion.DIRECT - 2, IdUnion.DIRECT + 3);
        expected.add(IdUnion.DIRECT + 10);
        expected.add(1L << 40);
        assertEquals(expected, union.toRuns());
    }

    @Test
    public void manySetsGiveWhatMergingThemOneByOneGives() {
        Random random = new Random(7);
        IdUnion union = new IdUnion();
        IdRuns merged = new IdRuns();
        for (int set = 0; set < 500; set++) {
            IdRuns runs = new IdRuns();
            for (int value = 0; value < 20; value++) {
                runs.add(random.nextInt(4_000));
            }
            union.addAll(runs);
            merged.addAll(runs);
        }
        assertEquals(merged, union.toRuns());
    }
}
