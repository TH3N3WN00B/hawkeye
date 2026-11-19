package gg.moonrise.hawkeye.core.repository.event;

import static org.junit.jupiter.api.Assertions.assertEquals;

import gg.moonrise.hawkeye.core.sql.SqliteHarness;
import gg.moonrise.hawkeye.entity.query.RollbackState;
import gg.moonrise.hawkeye.util.BlockPositions;
import gg.moonrise.hawkeye.util.EventIds;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link EventFilter#matches} (archive reads) and {@link EventSql} (hot reads) select the same events. */
public class EventFilterTest {

    private static final long T = EventIds.EPOCH_MILLIS + 86_400_000L * 200;

    @TempDir
    private Path folder;

    @Test
    public void javaAndSqlAgreeOnRandomFilters() throws SQLException {
        Random random = new Random(7);
        List<EventRow> rows = new ArrayList<>();
        for (int i = 0; i < 3_000; i++) {
            rows.add(new EventRow(EventIds.compose(T + i * 1000L, 1 + random.nextInt(3), 0), 1 + random.nextInt(2),
                    BlockPositions.pack(random.nextInt(200) - 100, random.nextInt(100), random.nextInt(200) - 100),
                    1 + random.nextInt(5), 1 + random.nextInt(3), 1 + random.nextInt(6), random.nextInt(3), 0,
                    random.nextInt(4) == 0 ? EventRow.ROLLED_BACK : 0, null));
        }
        try (SqliteHarness harness = SqliteHarness.open(folder)) {
            EventRepository repository = new EventRepository();
            harness.database().transaction(connection -> repository.insert(connection, harness.database().sql(), rows));
            for (int round = 0; round < 60; round++) {
                EventFilter filter = randomFilter(random);
                Set<Long> sql = harness.database().read(connection -> repository.find(connection,
                        harness.database().sql(), filter, 0, 10_000, true)).stream().map(EventRow::id)
                        .collect(Collectors.toSet());
                Set<Long> java = rows.stream().filter(filter::matches).map(EventRow::id).collect(Collectors.toSet());
                assertEquals(sql, java, "filter " + filter);
            }
        }
    }

    private static EventFilter randomFilter(Random random) {
        Set<Integer> worlds = random.nextBoolean() ? Set.of(1) : Set.of();
        List<EventFilter.WorldRange> positions = new ArrayList<>();
        EventFilter.Bounds bounds = EventFilter.Bounds.NONE;
        if (random.nextBoolean()) {
            int minX = random.nextInt(150) - 100;
            int minZ = random.nextInt(150) - 100;
            int maxX = minX + random.nextInt(60);
            int maxZ = minZ + random.nextInt(60);
            for (BlockPositions.Range range : BlockPositions.blockRanges(minX, minZ, maxX, maxZ)) {
                int chunkX = BlockPositions.chunkX(range.from());
                positions.add(new EventFilter.WorldRange(1, range.from(), range.to(),
                        chunkX == minX >> 4 ? minX & 15 : 0, chunkX == maxX >> 4 ? maxX & 15 : 15));
            }
            int minY = random.nextInt(60);
            bounds = new EventFilter.Bounds(minY, minY + random.nextInt(50), minZ, maxZ);
        }
        long minId = random.nextBoolean() ? EventIds.floor(T + random.nextInt(1_500_000)) : 0;
        RollbackState[] states = RollbackState.values();
        return new EventFilter(minId, Long.MAX_VALUE, worlds, positions, bounds, subset(random, 3), subset(random, 5),
                subset(random, 5), subset(random, 3), subset(random, 6), subset(random, 6),
                states[random.nextInt(states.length)]);
    }

    private static Set<Integer> subset(Random random, int max) {
        Set<Integer> values = new HashSet<>();
        if (random.nextInt(3) == 0) {
            for (int i = 1; i <= max; i++) {
                if (random.nextBoolean()) {
                    values.add(i);
                }
            }
        }
        return values;
    }
}
