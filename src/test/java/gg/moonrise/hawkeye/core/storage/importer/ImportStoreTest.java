package gg.moonrise.hawkeye.core.storage.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.core.repository.dictionary.ActorRepository;
import gg.moonrise.hawkeye.core.repository.dictionary.DictionaryRepository;
import gg.moonrise.hawkeye.core.repository.event.EventRepository;
import gg.moonrise.hawkeye.core.repository.importer.ImportStateRepository;
import gg.moonrise.hawkeye.core.repository.network.ServerRepository;
import gg.moonrise.hawkeye.core.sql.SqliteHarness;
import gg.moonrise.hawkeye.core.storage.archive.Archives;
import gg.moonrise.hawkeye.core.storage.event.EventStore;
import gg.moonrise.hawkeye.core.storage.event.QueryResolver;
import gg.moonrise.hawkeye.core.storage.shard.Shard;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.hawkeye.entity.event.PendingEvent;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.entity.importer.CheckpointResult;
import gg.moonrise.hawkeye.entity.importer.ImportState;
import gg.moonrise.hawkeye.entity.importer.SkipReason;
import gg.moonrise.hawkeye.entity.importer.source.SourceCursor;
import gg.moonrise.hawkeye.entity.importer.source.SourceTable;
import gg.moonrise.hawkeye.entity.query.Area;
import gg.moonrise.hawkeye.entity.query.LookupQuery;
import gg.moonrise.hawkeye.entity.query.ShardScope;
import gg.moonrise.hawkeye.entity.server.ServerNode;
import gg.moonrise.hawkeye.util.position.BlockPositions;
import gg.moonrise.hawkeye.util.event.EventIds;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class ImportStoreTest {

    private static final long T0 = EventIds.EPOCH_MILLIS + 86_400_000L * 30;
    private static final String SOURCE = "coreprotect:survival:1735689600:25.0";
    private static final ShardScope SURVIVAL = new ShardScope.Scopes(Set.of("survival"));

    @TempDir
    private Path folder;

    private SqliteHarness harness;
    private EventStore events;
    private ImportStore store;
    private Shard home;

    @BeforeEach
    public void setUp() throws SQLException {
        harness = SqliteHarness.open(folder);
        events = new EventStore(new EventRepository(), new QueryResolver(new DictionaryRepository(),
                new ActorRepository()), Archives.create());
        store = new ImportStore(new ImportStateRepository(), new ServerRepository(), events);
        home = harness.shard();
        harness.register("survival", "survival");
    }

    @AfterEach
    public void tearDown() {
        harness.close();
    }

    @Test
    public void beginCreatesTheStateAndItsImportNodeOnce() throws SQLException {
        assertEquals(Optional.empty(), store.load(home, SOURCE));
        ImportState state = store.begin(home, SOURCE, "survival", "survival", "rowid", 1_000);
        assertEquals(ImportState.fresh("survival-coreprotect", "rowid", 1_000), state);
        assertEquals(Optional.of(state), store.load(home, SOURCE));
        ServerNode node = store.node(home, state);
        assertEquals(new ServerNode(2, "survival-coreprotect", "survival", 0), node);

        assertEquals(state, store.begin(home, SOURCE, "survival", "survival", "rowid", 9_000), "already begun");
        assertEquals(2, events.servers(home).size(), "no second node");
    }

    @Test
    public void furtherSourcesTakeTheNextFreeNodeName() throws SQLException {
        harness.register("survival-coreprotect-2", "survival");
        store.begin(home, SOURCE, "survival", "survival", "rowid", 1_000);
        ImportState second = store.begin(home, SOURCE + "b", "survival", "survival", "time", 1_000);
        assertEquals("survival-coreprotect-3", second.node(), "-2 is a running server's name");
        assertEquals("time", second.order());
        assertTrue(store.node(home, second).imported());
    }

    @Test
    public void aWriteCommitsTheSegmentWithTheCheckpoint() throws SQLException {
        ImportState state = store.begin(home, SOURCE, "survival", "survival", "rowid", 1_000);
        ServerNode node = store.node(home, state);
        ImportState next = advanced(state, 2, 2_000);

        CheckpointResult result = store.write(home, node, "survival", batch(0, 2), Runnable::run, SOURCE, next);

        ImportState saved = assertInstanceOf(CheckpointResult.Saved.class, result).state();
        assertEquals(next.withRevision(2, 2_000), saved);
        assertEquals(Optional.of(saved), store.load(home, SOURCE));
        List<StoredEvent> found = events.find(home, LookupQuery.at(new Area.Everywhere()), SURVIVAL, 0, 10, false);
        assertEquals(List.of("survival-coreprotect", "survival-coreprotect"),
                found.stream().map(StoredEvent::server).toList(), "found by world scope, numbered by the import node");
        assertEquals(List.of(true, false), found.stream().map(StoredEvent::rolledBack).toList());
    }

    @Test
    public void aStaleRevisionIsSupersededAndWritesNothing() throws SQLException {
        ImportState state = store.begin(home, SOURCE, "survival", "survival", "rowid", 1_000);
        ServerNode node = store.node(home, state);
        ImportState first = advanced(state, 2, 2_000);
        assertInstanceOf(CheckpointResult.Saved.class, store.write(home, node, "survival", batch(0, 2),
                Runnable::run, SOURCE, first));

        // A second run that read the checkpoint at revision 1, too.
        CheckpointResult stale = store.write(home, node, "survival", batch(10, 3), Runnable::run, SOURCE,
                advanced(state, 3, 3_000));

        assertEquals(new CheckpointResult.Superseded(), stale);
        assertEquals(2, events.count(home, LookupQuery.at(new Area.Everywhere()), SURVIVAL), "its segment rolled back");
        assertEquals(first.withRevision(2, 2_000), store.load(home, SOURCE).orElseThrow());
    }

    @Test
    public void saveWritesTheCheckpointAlone() throws SQLException {
        ImportState state = store.begin(home, SOURCE, "survival", "survival", "rowid", 1_000);
        ImportState complete = new ImportState(state.revision(), state.node(), state.order(), state.cursors(), 0, 0,
                0, Map.of(SkipReason.NO_EQUIVALENT, 4L), true, state.startedMillis(), 5_000);

        ImportState saved = assertInstanceOf(CheckpointResult.Saved.class, store.save(home, SOURCE, complete)).state();

        assertEquals(2, saved.revision());
        assertEquals(saved, store.load(home, SOURCE).orElseThrow());
        assertEquals(new CheckpointResult.Superseded(), store.save(home, SOURCE, complete));
        assertEquals(0, events.count(home, LookupQuery.at(new Area.Everywhere()), SURVIVAL));
    }

    private static ImportState advanced(ImportState state, long rows, long now) {
        return new ImportState(state.revision(), state.node(), state.order(),
                Map.of(SourceTable.BLOCK, new SourceCursor(T0 / 1000, rows)), EventIds.slot(EventIds.floor(T0)), rows,
                0, Map.of(), false, state.startedMillis(), now);
    }

    private static List<PendingEvent> batch(int from, int count) {
        Actor alex = Actor.player(new UUID(0, 1), "Alex");
        List<PendingEvent> batch = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            batch.add(new PendingEvent(EventIds.slotStamp(EventIds.slot(EventIds.floor(T0)), from + i), "world",
                    BlockPositions.pack(from + i, 64, 0), alex, Action.BLOCK_BREAK, "minecraft:stone", "minecraft:air",
                    0, null, i == 0));
        }
        return batch;
    }
}
