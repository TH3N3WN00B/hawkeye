package gg.moonrise.hawkeye.core.storage;

import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.config.file.ArchiveSettings;
import gg.moonrise.hawkeye.config.file.StorageSettings;
import gg.moonrise.hawkeye.core.queue.IdAssigner;
import gg.moonrise.hawkeye.core.repository.ActorRepository;
import gg.moonrise.hawkeye.core.repository.DictionaryRepository;
import gg.moonrise.hawkeye.core.repository.EventRepository;
import gg.moonrise.hawkeye.core.repository.SegmentRepository;
import gg.moonrise.hawkeye.core.sql.Database;
import gg.moonrise.hawkeye.core.sql.DatabaseFactory;
import gg.moonrise.hawkeye.core.sql.SqliteHarness;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.hawkeye.entity.event.PendingEvent;
import gg.moonrise.hawkeye.entity.server.ServerNode;
import gg.moonrise.hawkeye.util.BlockPositions;
import gg.moonrise.hawkeye.util.EventIds;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The storage cost of block events on SQLite, in the hot table and after archiving, against
 * CoreProtect's relational {@code co_block} layout (its columns and its three indexes) holding the
 * same workload. A regression guard for both formats. Measured 2026-11-16: hot 73.1, archived 6.2 (one
 * segment of 60,000 events), CoreProtect 80.3 bytes per event; random positions and types over a
 * 400-block spread are the worst case for the archive, the codec stores real history in about 1.2.
 */
@Slf4j
public class StorageFootprintTest {

    private static final int EVENTS = 60_000;
    private static final double MAX_HOT_BYTES_PER_EVENT = 80;
    private static final double MAX_ARCHIVED_BYTES_PER_EVENT = 7.5;
    private static final long START = EventIds.EPOCH_MILLIS + 86_400_000L * 500;

    @TempDir
    private Path folder;

    @Test
    public void blockEventsCostFarLessThanCoreProtectsLayout() throws SQLException {
        List<Sample> workload = workload();
        double[] hawkeye = hawkeyeBytesPerEvent(workload);
        double coreProtect = coreProtectBytesPerEvent(workload);
        String report = String.format("SQLite bytes per block event: Hawkeye hot %.1f, archived %.1f; CoreProtect "
                + "layout %.1f (archive %.1fx smaller)", hawkeye[0], hawkeye[1], coreProtect, coreProtect / hawkeye[1]);
        log.info(report);
        System.out.println(report);
        assertTrue(hawkeye[0] < MAX_HOT_BYTES_PER_EVENT, report);
        assertTrue(hawkeye[1] < MAX_ARCHIVED_BYTES_PER_EVENT, report);
        assertTrue(hawkeye[1] * 10 < coreProtect, report);
    }

    private double[] hawkeyeBytesPerEvent(List<Sample> workload) throws SQLException {
        try (SqliteHarness harness = SqliteHarness.open(folder.resolve("hawkeye"))) {
            ServerNode node = harness.register("survival", "survival");
            Archive archive = new Archive(new EventRepository(), new SegmentRepository(), new SegmentCache());
            EventStore store = new EventStore(new EventRepository(), new QueryResolver(new DictionaryRepository(),
                    new ActorRepository()), archive);
            // The writer numbers ids on the one-second grid; the archive relies on that order.
            IdAssigner ids = new IdAssigner();
            List<PendingEvent> captured = new ArrayList<>();
            int sequence = 0;
            for (Sample sample : workload) {
                captured.add(new PendingEvent(EventIds.stamp(sample.millis(), sequence++ & EventIds.MAX_SEQUENCE),
                        "world", BlockPositions.pack(sample.x(), sample.y(), sample.z()), sample.actor(),
                        sample.place() ? Action.BLOCK_PLACE : Action.BLOCK_BREAK, sample.state(),
                        "minecraft:air", 0, null));
            }
            ids.add(captured);
            List<PendingEvent> numbered = ids.take(Long.MAX_VALUE / 2, true);
            for (int from = 0; from < numbered.size(); from += 2_000) {
                store.write(harness.shard(), node, "survival",
                        numbered.subList(from, Math.min(numbered.size(), from + 2_000)));
            }
            double hot = size(harness.database()) / (double) workload.size();
            ArchiveSettings settings = new ArchiveSettings();
            while (archive.compact(harness.shard(), settings, Long.MAX_VALUE / 4) > 0) {
                // Archive everything as the scheduled archiver would: one segment of all 60,000 events.
            }
            double archived = size(harness.database()) / (double) workload.size();
            return new double[] {hot, archived};
        }
    }

    private double coreProtectBytesPerEvent(List<Sample> workload) throws SQLException {
        Database database = new DatabaseFactory().open("coreprotect",
                new StorageSettings().withSqliteFile("coreprotect.db"), folder);
        try {
            database.transaction(connection -> {
                try (Statement statement = connection.createStatement()) {
                    // CoreProtect's SQLite co_block table and indexes (database/Database.java).
                    statement.execute("CREATE TABLE co_block (time INTEGER, user INTEGER, wid INTEGER, x INTEGER, "
                            + "y INTEGER, z INTEGER, type INTEGER, data INTEGER, meta BLOB, blockdata BLOB, "
                            + "action INTEGER, rolled_back INTEGER)");
                    statement.execute("CREATE INDEX block_index ON co_block(wid,x,z,time)");
                    statement.execute("CREATE INDEX block_user_index ON co_block(user,time)");
                    statement.execute("CREATE INDEX block_type_index ON co_block(type,time)");
                }
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO co_block VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                    for (Sample sample : workload) {
                        insert.setInt(1, (int) (sample.millis() / 1000));
                        insert.setInt(2, sample.actorId());
                        insert.setInt(3, 1);
                        insert.setInt(4, sample.x());
                        insert.setInt(5, sample.y());
                        insert.setInt(6, sample.z());
                        insert.setInt(7, sample.material());
                        insert.setInt(8, 0);
                        insert.setNull(9, Types.BLOB);
                        // CoreProtect stores the block data of a stateful block as a blob of blockdata-map ids.
                        if (sample.state().contains("[")) {
                            insert.setBytes(10, new byte[] {0, 0, 0, (byte) sample.stateId(), 0, 0, 0, 1});
                        } else {
                            insert.setNull(10, Types.BLOB);
                        }
                        insert.setInt(11, sample.place() ? 1 : 0);
                        insert.setInt(12, 0);
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
                return null;
            });
            return size(database) / (double) workload.size();
        } finally {
            database.close();
        }
    }

    private static long size(Database database) throws SQLException {
        return database.read(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
                statement.execute("VACUUM");
            }
            return pragma(connection, "page_count") * pragma(connection, "page_size");
        });
    }

    private static long pragma(Connection connection, String name) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("PRAGMA " + name)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    /**
     * Players building and digging in their own areas over two days. Positions, block types and timing
     * are uniformly random, which is the worst case for the archive's compression; real builds repeat
     * far more.
     */
    private static List<Sample> workload() {
        Random random = new Random(42);
        List<Actor> players = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            players.add(Actor.player(new UUID(0, i + 1), "player" + i));
        }
        String[] simple = {"minecraft:stone", "minecraft:dirt", "minecraft:cobblestone", "minecraft:oak_planks",
                "minecraft:glass", "minecraft:deepslate", "minecraft:sand", "minecraft:gravel"};
        String[] stateful = {"minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]",
                "minecraft:oak_log[axis=y]", "minecraft:torch", "minecraft:oak_slab[type=bottom,waterlogged=false]",
                "minecraft:chest[facing=north,type=single,waterlogged=false]",
                "minecraft:oak_door[facing=west,half=lower,hinge=left,open=false,powered=false]"};
        List<Sample> samples = new ArrayList<>(EVENTS);
        long millis = START;
        for (int i = 0; i < EVENTS; i++) {
            millis += random.nextInt(4_000);
            int actor = random.nextInt(players.size());
            int baseX = (actor % 8) * 400 - 1600;
            int baseZ = (actor / 8) * 400 - 1000;
            boolean stateFul = random.nextInt(10) < 3;
            int index = stateFul ? random.nextInt(stateful.length) : random.nextInt(simple.length);
            samples.add(new Sample(millis, players.get(actor), actor + 1, baseX + random.nextInt(48),
                    40 + random.nextInt(40), baseZ + random.nextInt(48), stateFul ? stateful[index] : simple[index],
                    (stateFul ? 100 : 0) + index, index + 1, random.nextBoolean()));
        }
        return samples;
    }

    private record Sample(long millis, Actor actor, int actorId, int x, int y, int z, String state, int stateId,
            int material, boolean place) {
    }
}
