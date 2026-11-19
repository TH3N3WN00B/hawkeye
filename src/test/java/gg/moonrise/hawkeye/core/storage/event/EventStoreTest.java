package gg.moonrise.hawkeye.core.storage.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.core.repository.dictionary.ActorRepository;
import gg.moonrise.hawkeye.core.repository.dictionary.DictionaryRepository;
import gg.moonrise.hawkeye.core.repository.event.EventRepository;
import gg.moonrise.hawkeye.core.sql.SqliteHarness;
import gg.moonrise.hawkeye.core.storage.archive.Archives;
import gg.moonrise.hawkeye.core.storage.shard.Shard;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.hawkeye.entity.event.PendingEvent;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.entity.payload.Payload;
import gg.moonrise.hawkeye.entity.query.Area;
import gg.moonrise.hawkeye.entity.query.LookupQuery;
import gg.moonrise.hawkeye.entity.query.RollbackState;
import gg.moonrise.hawkeye.entity.query.ServerScope;
import gg.moonrise.hawkeye.entity.query.ShardScope;
import gg.moonrise.hawkeye.entity.query.TimeWindow;
import gg.moonrise.hawkeye.entity.server.ServerNode;
import gg.moonrise.hawkeye.util.BlockPositions;
import gg.moonrise.hawkeye.util.EventIds;
import gg.moonrise.hawkeye.util.codec.PayloadCodec;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class EventStoreTest {

    private static final long T0 = EventIds.EPOCH_MILLIS + 86_400_000L * 400;
    private static final Actor ALEX = Actor.player(UUID.fromString("00000000-0000-0000-0000-00000000000a"), "Alex");
    private static final Actor STEVE = Actor.player(UUID.fromString("00000000-0000-0000-0000-00000000000b"), "Steve");
    private static final Actor TNT = Actor.source("tnt");
    private static final String STONE = "minecraft:stone";
    private static final String AIR = "minecraft:air";
    private static final String STAIRS = "minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]";

    @TempDir
    private Path folder;

    private SqliteHarness harness;
    private EventStore store;
    private ServerNode survival;
    private final AtomicInteger sequence = new AtomicInteger();

    @BeforeEach
    public void setUp() throws SQLException {
        harness = SqliteHarness.open(folder);
        store = new EventStore(new EventRepository(), new QueryResolver(new DictionaryRepository(),
                new ActorRepository()), Archives.create());
        survival = harness.register("survival", "survival");
    }

    @AfterEach
    public void tearDown() {
        harness.close();
    }

    @Test
    public void writesAndReadsBackEveryField() throws SQLException {
        byte[] sign = PayloadCodec.encode(new Payload.Text("hello"));
        write(survival, "survival", List.of(
                event(T0, "world", 10, 64, -20, ALEX, Action.BLOCK_BREAK, STAIRS, AIR, 0, null),
                event(T0 + 5, "world", 10, 64, -20, STEVE, Action.CHAT, "", null, 0, sign),
                event(T0 + 9, "world", 11, 65, -21, ALEX, Action.CONTAINER_WITHDRAW, "minecraft:diamond", null, 12,
                        null)));

        List<StoredEvent> events = find(LookupQuery.at(new Area.Everywhere()));
        assertEquals(3, events.size());
        StoredEvent withdraw = events.getFirst();
        assertEquals(Action.CONTAINER_WITHDRAW, withdraw.action());
        assertEquals("minecraft:diamond", withdraw.subject());
        assertEquals(12, withdraw.amount());
        assertNull(withdraw.secondary());
        assertEquals("survival", withdraw.server());
        StoredEvent chat = events.get(1);
        assertEquals("Steve", chat.actor().name());
        assertEquals(new Payload.Text("hello"), chat.payload().orElseThrow());
        StoredEvent broke = events.get(2);
        assertEquals(STAIRS, broke.subject());
        assertEquals(AIR, broke.secondary());
        assertEquals("world", broke.world());
        assertEquals(10, broke.x());
        assertEquals(64, broke.y());
        assertEquals(-20, broke.z());
        assertEquals(T0, broke.epochMillis());
        assertFalse(broke.rolledBack());
    }

    @Test
    public void retryingABatchWritesItOnce() throws SQLException {
        List<PendingEvent> batch = List.of(
                event(T0, "world", 0, 64, 0, ALEX, Action.BLOCK_PLACE, STONE, AIR, 0, null),
                event(T0, "world", 1, 64, 0, ALEX, Action.BLOCK_PLACE, STONE, AIR, 0, null));
        assertEquals(2, write(survival, "survival", batch));
        assertEquals(0, write(survival, "survival", batch));
        assertEquals(2, store.count(harness.shard(), LookupQuery.at(new Area.Everywhere()), all()));
    }

    @Test
    public void aBoxIsExactAcrossChunkBordersAndNegativeCoordinates() throws SQLException {
        List<PendingEvent> events = new ArrayList<>();
        for (int x = -40; x <= 40; x += 4) {
            for (int z = -40; z <= 40; z += 4) {
                for (int y : new int[] {-64, 0, 70, 319}) {
                    events.add(event(T0, "world", x, y, z, ALEX, Action.BLOCK_PLACE, STONE, AIR, 0, null));
                }
            }
        }
        events.add(event(T0, "nether", 0, 70, 0, ALEX, Action.BLOCK_PLACE, STONE, AIR, 0, null));
        write(survival, "survival", events);

        Area.Box box = new Area.Box("world", -17, 0, -9, 13, 100, 31);
        long expected = events.stream()
                .filter(event -> event.world().equals("world"))
                .filter(event -> box.contains(BlockPositions.x(event.position()), BlockPositions.y(event.position()),
                        BlockPositions.z(event.position())))
                .count();
        LookupQuery query = LookupQuery.at(box);
        assertEquals(expected, store.count(harness.shard(), query, all()));
        List<StoredEvent> found = store.find(harness.shard(), query, all(), 0, 10_000, true);
        assertEquals(expected, found.size());
        assertTrue(found.stream().allMatch(event -> box.contains(event.x(), event.y(), event.z())));
    }

    @Test
    public void aWideBoxUsesEdgeAndInteriorRangesAndStaysExact() throws SQLException {
        List<PendingEvent> events = new ArrayList<>();
        for (int x = -3000; x <= 3000; x += 250) {
            for (int z : new int[] {-500, -101, -100, 0, 100, 101, 500}) {
                events.add(event(T0, "world", x, 64, z, ALEX, Action.BLOCK_PLACE, STONE, AIR, 0, null));
            }
        }
        write(survival, "survival", events);
        Area.Box box = new Area.Box("world", -2001, -64, -100, 2010, 320, 100);
        long expected = events.stream().filter(event -> box.contains(BlockPositions.x(event.position()),
                BlockPositions.y(event.position()), BlockPositions.z(event.position()))).count();
        assertEquals(expected, store.count(harness.shard(), LookupQuery.at(box), all()));
    }

    @Test
    public void theInspectorReadsOneBlockNewestFirst() throws SQLException {
        write(survival, "survival", List.of(
                event(T0, "world", 5, 70, 5, ALEX, Action.BLOCK_PLACE, STONE, AIR, 0, null),
                event(T0 + 1000, "world", 5, 70, 5, STEVE, Action.BLOCK_BREAK, STONE, AIR, 0, null),
                event(T0 + 2000, "world", 5, 71, 5, STEVE, Action.BLOCK_PLACE, STONE, AIR, 0, null)));
        List<StoredEvent> history = find(LookupQuery.at(new Area.Block("world", 5, 70, 5)));
        assertEquals(List.of(Action.BLOCK_BREAK, Action.BLOCK_PLACE), history.stream().map(StoredEvent::action).toList());
    }

    @Test
    public void filtersByActorTypeActionAndTime() throws SQLException {
        write(survival, "survival", List.of(
                event(T0, "world", 0, 64, 0, ALEX, Action.BLOCK_PLACE, STAIRS, AIR, 0, null),
                event(T0 + 60_000, "world", 1, 64, 0, ALEX, Action.BLOCK_BREAK, STONE, AIR, 0, null),
                event(T0 + 120_000, "world", 2, 64, 0, STEVE, Action.BLOCK_BREAK, STAIRS, AIR, 0, null),
                event(T0 + 180_000, "world", 3, 64, 0, TNT, Action.BLOCK_BREAK, STONE, AIR, 0, null)));
        LookupQuery base = LookupQuery.at(new Area.Everywhere());

        assertEquals(2, find(withActors(base, Set.of("alex"))).size(), "names match case-insensitively");
        assertEquals(1, find(withActors(base, Set.of("#tnt"))).size());
        assertEquals(0, find(withActors(base, Set.of("nobody"))).size(), "an unknown actor matches nothing");
        assertEquals(2, find(withTypes(base, Set.of("minecraft:oak_stairs"))).size(), "a material matches its states");
        assertEquals(3, find(base.withActions(Set.of(Action.BLOCK_BREAK))).size());
        assertEquals(2, find(base.withTime(new TimeWindow(T0 + 60_000, T0 + 120_000))).size());
        LookupQuery excluding = new LookupQuery(base.servers(), base.area(), base.time(), Set.of(), Set.of("steve"),
                Set.of(), Set.of(), Set.of("minecraft:oak_stairs"), RollbackState.ANY);
        assertEquals(2, find(excluding).size());
    }

    @Test
    public void pagesWithAKeysetCursor() throws SQLException {
        List<PendingEvent> events = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            events.add(event(T0 + i, "world", i, 64, 0, ALEX, Action.BLOCK_PLACE, STONE, AIR, 0, null));
        }
        write(survival, "survival", events);
        LookupQuery query = LookupQuery.at(new Area.Everywhere());
        List<StoredEvent> first = store.find(harness.shard(), query, all(), 0, 10, true);
        List<StoredEvent> second = store.find(harness.shard(), query, all(), first.getLast().id(), 10, true);
        List<StoredEvent> third = store.find(harness.shard(), query, all(), second.getLast().id(), 10, true);
        assertEquals(List.of(10, 10, 5), List.of(first.size(), second.size(), third.size()));
        assertEquals(24, first.getFirst().x());
        assertEquals(0, third.getLast().x());
    }

    @Test
    public void rollbackFlagsAreSetClearedAndFiltered() throws SQLException {
        write(survival, "survival", List.of(
                event(T0, "world", 0, 64, 0, ALEX, Action.BLOCK_PLACE, STONE, AIR, 0, null),
                event(T0 + 1, "world", 1, 64, 0, ALEX, Action.BLOCK_PLACE, STONE, AIR, 0, null)));
        LookupQuery query = LookupQuery.at(new Area.Everywhere());
        long id = find(query).getFirst().id();
        assertEquals(1, store.setRolledBack(harness.shard(), List.of(id), true));
        assertEquals(1, store.count(harness.shard(), query.withRollbackState(RollbackState.ROLLED_BACK), all()));
        assertEquals(1, store.count(harness.shard(), query.withRollbackState(RollbackState.ACTIVE), all()));
        assertTrue(find(query).getFirst().rolledBack());
        store.setRolledBack(harness.shard(), List.of(id), false);
        assertEquals(0, store.count(harness.shard(), query.withRollbackState(RollbackState.ROLLED_BACK), all()));
    }

    @Test
    public void purgesInBatchesOldestFirst() throws SQLException {
        List<PendingEvent> events = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            events.add(event(T0 + i * 1000L, "world", i, 64, 0, ALEX, Action.BLOCK_PLACE, STONE, AIR, 0, null));
        }
        write(survival, "survival", events);
        assertEquals(10, store.purgeBatch(harness.shard(), T0 + 20_000, List.of(), 10));
        assertEquals(10, store.purgeBatch(harness.shard(), T0 + 20_000, List.of(), 10));
        assertEquals(0, store.purgeBatch(harness.shard(), T0 + 20_000, List.of(), 10));
        List<StoredEvent> left = find(LookupQuery.at(new Area.Everywhere()));
        assertEquals(10, left.size());
        assertEquals(20, left.getLast().x());
    }

    @Test
    public void serversSharingADatabaseKeepTheirWorldsApart() throws SQLException {
        ServerNode creative = harness.register("creative", "creative");
        write(survival, "survival", List.of(event(T0, "world", 0, 64, 0, ALEX, Action.BLOCK_PLACE, STONE, AIR, 0,
                null)));
        writeAs(harness.otherServerView(), creative, "creative", List.of(
                event(T0, "world", 0, 64, 0, ALEX, Action.BLOCK_BREAK, STONE, AIR, 0, null),
                event(T0 + 1, "world", 1, 64, 0, STEVE, Action.BLOCK_BREAK, STONE, AIR, 0, null)));

        LookupQuery everywhere = LookupQuery.at(new Area.Everywhere());
        ShardScope survivalOnly = new ShardScope.Scopes(Set.of("survival"));
        assertEquals(1, store.count(harness.shard(), everywhere, survivalOnly));
        assertEquals(1, store.count(harness.shard(), LookupQuery.at(new Area.Block("world", 0, 64, 0)), survivalOnly));
        assertEquals(3, store.count(harness.shard(), everywhere, all()));
        List<StoredEvent> network = find(everywhere);
        assertEquals(Set.of("survival", "creative"), Set.copyOf(network.stream().map(StoredEvent::server).toList()));
        assertTrue(creative.node() != survival.node());
        assertEquals(0, store.count(harness.shard(), everywhere, new ShardScope.Scopes(Set.of())));
    }

    @Test
    public void aRenamedPlayerKeepsTheirHistory() throws SQLException {
        Actor renamed = Actor.player(UUID.fromString(ALEX.key()), "Alexander");
        write(survival, "survival", List.of(event(T0, "world", 0, 64, 0, ALEX, Action.BLOCK_PLACE, STONE, AIR, 0,
                null)));
        write(survival, "survival", List.of(event(T0 + 1, "world", 1, 64, 0, renamed, Action.BLOCK_PLACE, STONE, AIR,
                0, null)));
        LookupQuery byNewName = withActors(LookupQuery.at(new Area.Everywhere()), Set.of("alexander"));
        assertEquals(2, find(byNewName).size());
        assertEquals(0, find(withActors(LookupQuery.at(new Area.Everywhere()), Set.of("alex"))).size());
    }

    private int write(ServerNode node, String scope, List<PendingEvent> events) throws SQLException {
        return writeAs(harness.shard(), node, scope, events);
    }

    private int writeAs(Shard shard, ServerNode node, String scope, List<PendingEvent> events) throws SQLException {
        return store.write(shard, node, scope, events);
    }

    private List<StoredEvent> find(LookupQuery query) throws SQLException {
        return store.find(harness.shard(), query, all(), 0, 1000, true);
    }

    private static ShardScope all() {
        return new ShardScope.All();
    }

    private static LookupQuery withActors(LookupQuery query, Set<String> actors) {
        return new LookupQuery(new ServerScope.Local(), query.area(), query.time(), actors, Set.of(), Set.of(),
                Set.of(), Set.of(), RollbackState.ANY);
    }

    private static LookupQuery withTypes(LookupQuery query, Set<String> types) {
        return new LookupQuery(new ServerScope.Local(), query.area(), query.time(), Set.of(), Set.of(), Set.of(),
                types, Set.of(), RollbackState.ANY);
    }

    private PendingEvent event(long millis, String world, int x, int y, int z, Actor actor, Action action,
            String subject, String secondary, int amount, byte[] data) {
        return new PendingEvent(EventIds.stamp(millis, sequence.getAndIncrement() & EventIds.MAX_SEQUENCE), world,
                BlockPositions.pack(x, y, z), actor, action, subject, secondary, amount, data);
    }
}
