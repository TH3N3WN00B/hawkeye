package gg.moonrise.hawkeye.core.service.rollback.plan;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.core.service.rollback.RollbackMode;
import gg.moonrise.hawkeye.core.service.rollback.RollbackOrigin;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.entity.payload.Payload;
import gg.moonrise.hawkeye.entity.payload.SignSides;
import gg.moonrise.hawkeye.entity.query.Area;
import gg.moonrise.hawkeye.entity.query.LookupQuery;
import gg.moonrise.hawkeye.entity.query.RollbackState;
import gg.moonrise.hawkeye.util.position.BlockPositions;
import gg.moonrise.hawkeye.util.codec.PayloadCodec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

public class RollbackPlannerTest {

    private static final Actor ALEX = Actor.player(UUID.fromString("00000000-0000-0000-0000-00000000000a"), "Alex");
    private static final String STONE = "minecraft:stone";
    private static final String DIRT = "minecraft:dirt";
    private static final String AIR = "minecraft:air";
    private static final String WATER = "minecraft:water[level=0]";
    private static final String CHEST = "minecraft:chest[facing=north,type=single,waterlogged=false]";
    private static final String SIGN = "minecraft:oak_sign[rotation=0,waterlogged=false]";
    private static final String DIAMOND = "minecraft:diamond";
    private static final PlanOptions ALL = new PlanOptions(true, true, null);

    private final RollbackPlanner planner = new RollbackPlanner();

    @Test
    public void rollbackPutsBackWhatWasThereBeforeTheOldestEvent() {
        List<StoredEvent> events = List.of(
                block(1, Action.BLOCK_BREAK, 0, 64, 0, STONE, AIR),
                block(2, Action.BLOCK_PLACE, 0, 64, 0, DIRT, AIR),
                block(3, Action.BLOCK_BREAK, 0, 64, 0, DIRT, AIR),
                block(4, Action.BLOCK_PLACE, 1, 64, 0, STONE, WATER),
                block(5, Action.BLOCK_CHANGE, 2, 64, 0, DIRT, "minecraft:grass_block[snowy=false]"));

        RollbackPlan plan = planner.plan(RollbackMode.ROLLBACK, events, ALL);

        assertEquals(List.of(1L, 2L, 3L, 4L, 5L), plan.eventIds());
        assertEquals(List.of(
                setBlock(0, 64, 0, STONE),
                setBlock(1, 64, 0, WATER),
                setBlock(2, 64, 0, "minecraft:grass_block[snowy=false]")), plan.changes().toList());
    }

    @Test
    public void restorePutsBackWhatTheNewestEventMade() {
        List<StoredEvent> events = List.of(
                block(1, Action.BLOCK_BREAK, 0, 64, 0, STONE, AIR),
                block(2, Action.BLOCK_PLACE, 0, 64, 0, DIRT, AIR),
                block(3, Action.BLOCK_PLACE, 1, 64, 0, STONE, WATER),
                block(4, Action.BLOCK_BREAK, 1, 64, 0, STONE, null),
                block(5, Action.BLOCK_CHANGE, 2, 64, 0, DIRT, "minecraft:grass_block[snowy=false]"));

        RollbackPlan plan = planner.plan(RollbackMode.RESTORE, events, ALL);

        assertEquals(List.of(
                setBlock(0, 64, 0, DIRT),
                setBlock(1, 64, 0, AIR),
                setBlock(2, 64, 0, DIRT)), plan.changes().toList());
    }

    @Test
    public void rollbackThenRestoreAreSymmetricForOneEvent() {
        StoredEvent broke = block(1, Action.BLOCK_BREAK, 5, 70, -3, STONE, AIR);
        assertEquals(List.of(setBlock(5, 70, -3, STONE)),
                planner.plan(RollbackMode.ROLLBACK, List.of(broke), ALL).changes().toList());
        assertEquals(List.of(setBlock(5, 70, -3, AIR)),
                planner.plan(RollbackMode.RESTORE, List.of(broke), ALL).changes().toList());
        StoredEvent placed = block(2, Action.BLOCK_PLACE, 5, 70, -3, DIRT, STONE);
        assertEquals(List.of(setBlock(5, 70, -3, STONE)),
                planner.plan(RollbackMode.ROLLBACK, List.of(placed), ALL).changes().toList());
        assertEquals(List.of(setBlock(5, 70, -3, DIRT)),
                planner.plan(RollbackMode.RESTORE, List.of(placed), ALL).changes().toList());
    }

    @Test
    public void thePlanDoesNotDependOnTheOrderEventsArriveIn() {
        List<StoredEvent> events = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            events.add(block(i + 1, i % 2 == 0 ? Action.BLOCK_BREAK : Action.BLOCK_PLACE, i % 5, 64, i % 3,
                    i % 2 == 0 ? STONE : DIRT, AIR));
        }
        RollbackPlan ordered = planner.plan(RollbackMode.ROLLBACK, events, ALL);
        List<StoredEvent> shuffled = new ArrayList<>(events);
        Collections.shuffle(shuffled, new Random(7));
        assertEquals(ordered, planner.plan(RollbackMode.ROLLBACK, shuffled, ALL));
    }

    @Test
    public void aBrokenContainerGetsItsContentsBack() {
        List<Payload.Slot> slots = List.of(new Payload.Slot(0, new byte[] {1, 2}), new Payload.Slot(13, new byte[] {3}));
        StoredEvent broke = block(1, Action.BLOCK_BREAK, 0, 64, 0, CHEST, AIR, new Payload.Inventory(slots));

        RollbackPlan plan = planner.plan(RollbackMode.ROLLBACK, List.of(broke), ALL);

        PlannedChange.SetBlock set = assertInstanceOf(PlannedChange.SetBlock.class, plan.changes().findFirst()
                .orElseThrow());
        assertEquals(CHEST, set.state());
        assertEquals(slots, set.contents());
        PlannedChange.SetBlock restored = assertInstanceOf(PlannedChange.SetBlock.class,
                planner.plan(RollbackMode.RESTORE, List.of(broke), ALL).changes().findFirst().orElseThrow());
        assertEquals(AIR, restored.state());
        assertTrue(restored.contents().isEmpty());
    }

    @Test
    public void aBrokenSignGetsItsTextBack() {
        SignSides text = new SignSides(List.of(json("hello"), json("there")), List.of(json("back")));
        StoredEvent broke = block(1, Action.BLOCK_BREAK, 0, 64, 0, SIGN, AIR, new Payload.Sign(SignSides.EMPTY, text));

        PlannedChange.SetBlock set = assertInstanceOf(PlannedChange.SetBlock.class,
                planner.plan(RollbackMode.ROLLBACK, List.of(broke), ALL).changes().findFirst().orElseThrow());

        assertEquals(SIGN, set.state());
        assertEquals(SignPatch.full(text), set.sign());
    }

    @Test
    public void signEditsRollBackEachChangedSideToItsOldestBefore() {
        SignSides blank = new SignSides(List.of(), List.of());
        SignSides frontA = new SignSides(List.of(json("a")), List.of());
        SignSides frontB = new SignSides(List.of(json("b")), List.of());
        SignSides frontBBackC = new SignSides(List.of(json("b")), List.of(json("c")));
        List<StoredEvent> edits = List.of(
                block(1, Action.SIGN_EDIT, 0, 64, 0, SIGN, null, new Payload.Sign(blank, frontA)),
                block(2, Action.SIGN_EDIT, 0, 64, 0, SIGN, null, new Payload.Sign(frontA, frontB)),
                block(3, Action.SIGN_EDIT, 0, 64, 0, SIGN, null, new Payload.Sign(frontB, frontBBackC)));

        PlannedChange.SetSignText rollback = assertInstanceOf(PlannedChange.SetSignText.class,
                planner.plan(RollbackMode.ROLLBACK, edits, ALL).changes().findFirst().orElseThrow());
        assertEquals(new SignPatch(new SignSides(List.of(), List.of()), true, true), rollback.sign());

        PlannedChange.SetSignText partial = assertInstanceOf(PlannedChange.SetSignText.class,
                planner.plan(RollbackMode.ROLLBACK, edits.subList(1, 2), ALL).changes().findFirst().orElseThrow());
        assertEquals(new SignPatch(new SignSides(List.of(json("a")), List.of()), true, false), partial.sign());

        PlannedChange.SetSignText restore = assertInstanceOf(PlannedChange.SetSignText.class,
                planner.plan(RollbackMode.RESTORE, edits, ALL).changes().findFirst().orElseThrow());
        assertEquals(new SignPatch(frontBBackC, true, true), restore.sign());
    }

    @Test
    public void anEditedThenBrokenSignComesBackWithItsTextBeforeTheEdit() {
        SignSides before = new SignSides(List.of(json("old")), List.of(json("back")));
        SignSides after = new SignSides(List.of(json("new")), List.of(json("back")));
        List<StoredEvent> events = List.of(
                block(1, Action.SIGN_EDIT, 0, 64, 0, SIGN, null, new Payload.Sign(before, after)),
                block(2, Action.BLOCK_BREAK, 0, 64, 0, SIGN, AIR, new Payload.Sign(SignSides.EMPTY, after)));

        PlannedChange.SetBlock set = assertInstanceOf(PlannedChange.SetBlock.class,
                planner.plan(RollbackMode.ROLLBACK, events, ALL).changes().findFirst().orElseThrow());

        assertEquals(SIGN, set.state());
        assertEquals(SignPatch.full(before), set.sign());
        assertEquals(List.of(setBlock(0, 64, 0, AIR)), planner.plan(RollbackMode.RESTORE, events, ALL).changes()
                .toList());
    }

    @Test
    public void aPlacedThenEditedSignIsRestoredWithItsText() {
        SignSides text = new SignSides(List.of(json("hi")), List.of());
        List<StoredEvent> events = List.of(
                block(1, Action.BLOCK_PLACE, 0, 64, 0, SIGN, AIR),
                block(2, Action.SIGN_EDIT, 0, 64, 0, SIGN, null, new Payload.Sign(SignSides.EMPTY, text)));

        PlannedChange.SetBlock restored = assertInstanceOf(PlannedChange.SetBlock.class,
                planner.plan(RollbackMode.RESTORE, events, ALL).changes().findFirst().orElseThrow());
        assertEquals(SIGN, restored.state());
        assertEquals(new SignPatch(new SignSides(List.of(json("hi")), List.of()), true, false), restored.sign());
        assertEquals(List.of(setBlock(0, 64, 0, AIR)), planner.plan(RollbackMode.ROLLBACK, events, ALL).changes()
                .toList());
    }

    @Test
    public void containerChangesAreReversedAndMergedPerItem() {
        byte[] named = {9, 9, 9};
        List<StoredEvent> events = List.of(
                item(1, Action.CONTAINER_DEPOSIT, DIAMOND, 5, null),
                item(2, Action.CONTAINER_WITHDRAW, DIAMOND, 2, null),
                item(3, Action.CONTAINER_WITHDRAW, DIAMOND, 1, new Payload.Item(named)),
                item(4, Action.CONTAINER_DEPOSIT, "minecraft:stick", 3, null),
                item(5, Action.CONTAINER_WITHDRAW, "minecraft:stick", 3, null));

        RollbackPlan rollback = planner.plan(RollbackMode.ROLLBACK, events, ALL);
        assertEquals(List.of(
                new PlannedChange.AdjustItems(position(0, 64, 0), DIAMOND, new byte[0], -3),
                new PlannedChange.AdjustItems(position(0, 64, 0), DIAMOND, named, 1)), rollback.changes().toList());
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L), rollback.eventIds());

        RollbackPlan restore = planner.plan(RollbackMode.RESTORE, events, ALL);
        assertEquals(List.of(
                new PlannedChange.AdjustItems(position(0, 64, 0), DIAMOND, new byte[0], 3),
                new PlannedChange.AdjustItems(position(0, 64, 0), DIAMOND, named, -1)), restore.changes().toList());
    }

    @Test
    public void containerChangesCanBeTurnedOff() {
        PlanOptions blocksOnly = new PlanOptions(false, true, null);
        RollbackPlan plan = planner.plan(RollbackMode.ROLLBACK,
                List.of(item(1, Action.CONTAINER_DEPOSIT, DIAMOND, 5, null)), blocksOnly);
        assertTrue(plan.isEmpty());
        LookupQuery query = planner.query(LookupQuery.at(new Area.Everywhere()), RollbackMode.ROLLBACK, blocksOnly)
                .orElseThrow();
        assertFalse(query.actions().contains(Action.CONTAINER_DEPOSIT));
        assertTrue(query.actions().contains(Action.BLOCK_BREAK));
    }

    @Test
    public void killsAreRespawnedOnRollbackOnly() {
        byte[] zombie = {4, 2};
        List<StoredEvent> events = List.of(
                kill(1, new Payload.Entity(zombie)),
                kill(2, null));

        RollbackPlan rollback = planner.plan(RollbackMode.ROLLBACK, events, ALL);
        assertEquals(List.of(new PlannedChange.SpawnEntity(position(0, 64, 0), zombie)), rollback.changes().toList());
        assertEquals(List.of(1L), rollback.eventIds());

        assertTrue(planner.plan(RollbackMode.RESTORE, events, ALL).isEmpty());
        assertTrue(planner.plan(RollbackMode.ROLLBACK, events, new PlanOptions(true, false, null)).isEmpty());
        assertFalse(planner.query(LookupQuery.at(new Area.Everywhere()), RollbackMode.RESTORE, ALL).orElseThrow()
                .actions().contains(Action.ENTITY_KILL));
    }

    @Test
    public void removedEntitiesAreRespawnedOnRollbackOnly() {
        byte[] painting = {7, 7};
        List<StoredEvent> events = List.of(
                entity(1, Action.ENTITY_REMOVE, "minecraft:painting", new Payload.Entity(painting)),
                entity(2, Action.ENTITY_REMOVE, "minecraft:item_frame", null));

        RollbackPlan rollback = planner.plan(RollbackMode.ROLLBACK, events, ALL);
        assertEquals(List.of(new PlannedChange.SpawnEntity(position(0, 64, 0), painting)),
                rollback.changes().toList());
        assertEquals(List.of(1L), rollback.eventIds());

        assertTrue(planner.plan(RollbackMode.RESTORE, events, ALL).isEmpty());
        assertTrue(planner.plan(RollbackMode.ROLLBACK, events, new PlanOptions(true, false, null)).isEmpty());
    }

    @Test
    public void spawnedEntitiesAreRemovedByTheirUuidOnRollbackOnly() {
        UUID golem = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
        List<StoredEvent> events = List.of(
                entity(1, Action.ENTITY_SPAWN, "minecraft:iron_golem", new Payload.EntityRef(golem)),
                entity(2, Action.ENTITY_SPAWN, "minecraft:zombie", null),
                entity(3, Action.ENTITY_SPAWN, "minecraft:zombie", new Payload.Text("not a reference")));

        RollbackPlan rollback = planner.plan(RollbackMode.ROLLBACK, events, ALL);
        assertEquals(List.of(new PlannedChange.RemoveEntity(position(0, 64, 0), golem)), rollback.changes().toList());
        assertEquals(List.of(1L), rollback.eventIds());
        assertEquals(0, rollback.blocks());

        assertTrue(planner.plan(RollbackMode.RESTORE, events, ALL).isEmpty());
        assertTrue(planner.plan(RollbackMode.ROLLBACK, events, new PlanOptions(true, false, null)).isEmpty());
        LookupQuery restore = planner.query(LookupQuery.at(new Area.Everywhere()), RollbackMode.RESTORE, ALL)
                .orElseThrow();
        assertFalse(restore.actions().contains(Action.ENTITY_SPAWN));
        assertFalse(restore.actions().contains(Action.ENTITY_REMOVE));
        LookupQuery noEntities = planner.query(LookupQuery.at(new Area.Everywhere()), RollbackMode.ROLLBACK,
                new PlanOptions(true, false, null)).orElseThrow();
        assertFalse(noEntities.actions().contains(Action.ENTITY_SPAWN));
    }

    @Test
    public void aSpawnedEntityIsRemovedInTheChunkItWasSpawnedIn() {
        UUID boat = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
        StoredEvent spawned = new StoredEvent(1, "server", "world", position(40, 64, -20), ALEX, Action.ENTITY_SPAWN,
                "minecraft:oak_boat", null, 0, false, PayloadCodec.encode(new Payload.EntityRef(boat)));

        ChunkPlan chunk = planner.plan(RollbackMode.ROLLBACK, List.of(spawned), ALL).chunks().getFirst();

        assertEquals("world", chunk.world());
        assertEquals(2, chunk.chunkX());
        assertEquals(-2, chunk.chunkZ());
    }

    @Test
    public void entityInteractionsAreNeverReversed() {
        List<StoredEvent> events = new ArrayList<>();
        long id = 1;
        for (Action action : List.of(Action.ENTITY_SHEAR, Action.ENTITY_NAME, Action.ENTITY_LEASH,
                Action.ENTITY_UNLEASH, Action.ENTITY_TAME, Action.ENTITY_DYE)) {
            events.add(entity(id++, action, "minecraft:sheep", new Payload.Text("red")));
        }
        assertTrue(planner.plan(RollbackMode.ROLLBACK, events, ALL).isEmpty());
        LookupQuery interactions = LookupQuery.at(new Area.Everywhere())
                .withActions(EnumSet.of(Action.ENTITY_SHEAR, Action.ENTITY_DYE));
        assertTrue(planner.query(interactions, RollbackMode.ROLLBACK, ALL).isEmpty());
    }

    @Test
    public void eventsThatAreNotReversibleAreIgnored() {
        List<StoredEvent> events = List.of(
                block(1, Action.INTERACT, 0, 64, 0, "minecraft:lever[powered=true]", "minecraft:lever[powered=false]"),
                new StoredEvent(2, "server", "world", position(0, 64, 0), ALEX, Action.CHAT, "", null, 0, false,
                        PayloadCodec.encode(new Payload.Text("hi"))));
        assertTrue(planner.plan(RollbackMode.ROLLBACK, events, ALL).isEmpty());
        LookupQuery chatOnly = LookupQuery.at(new Area.Everywhere()).withActions(Set.of(Action.CHAT));
        assertTrue(planner.query(chatOnly, RollbackMode.ROLLBACK, ALL).isEmpty());
    }

    @Test
    public void theQueryReadsOnlyWhatTheModeReverses() {
        LookupQuery query = LookupQuery.at(new Area.Everywhere())
                .withActions(EnumSet.of(Action.BLOCK_BREAK, Action.ITEM_DROP));
        LookupQuery rollback = planner.query(query, RollbackMode.ROLLBACK, ALL).orElseThrow();
        assertEquals(Set.of(Action.BLOCK_BREAK), rollback.actions());
        assertEquals(RollbackState.ACTIVE, rollback.rollbackState());
        LookupQuery restore = planner.query(query, RollbackMode.RESTORE, ALL).orElseThrow();
        assertEquals(RollbackState.ROLLED_BACK, restore.rollbackState());
        LookupQuery everything = planner.query(LookupQuery.at(new Area.Everywhere()), RollbackMode.ROLLBACK, ALL)
                .orElseThrow();
        assertEquals(EnumSet.of(Action.BLOCK_BREAK, Action.BLOCK_PLACE, Action.BLOCK_CHANGE, Action.SIGN_EDIT,
                Action.CONTAINER_DEPOSIT, Action.CONTAINER_WITHDRAW, Action.ENTITY_KILL, Action.ENTITY_SPAWN,
                Action.ENTITY_REMOVE), everything.actions());
    }

    @Test
    public void chunksNearestTheIssuerComeFirstAndOtherWorldsLast() {
        List<StoredEvent> events = List.of(
                block(1, Action.BLOCK_BREAK, 160, 64, 0, STONE, AIR),
                block(2, Action.BLOCK_BREAK, 20, 64, 0, STONE, AIR),
                block(3, Action.BLOCK_BREAK, -50, 64, 0, STONE, AIR),
                new StoredEvent(4, "server", "nether", position(0, 64, 0), ALEX, Action.BLOCK_BREAK, STONE, AIR, 0,
                        false, null),
                block(5, Action.BLOCK_BREAK, 3, 64, 3, STONE, AIR));

        RollbackPlan plan = planner.plan(RollbackMode.ROLLBACK, events,
                new PlanOptions(true, true, new RollbackOrigin("world", 8, 8)));

        assertEquals(List.of("world:0", "world:1", "world:-4", "world:10", "nether:0"),
                plan.chunks().stream().map(chunk -> chunk.world() + ":" + chunk.chunkX()).toList());
        RollbackPlan fixed = planner.plan(RollbackMode.ROLLBACK, events, ALL);
        assertEquals(List.of("nether:0", "world:-4", "world:0", "world:1", "world:10"),
                fixed.chunks().stream().map(chunk -> chunk.world() + ":" + chunk.chunkX()).toList());
    }

    @Test
    public void aChunkChangesBlocksThenRespawnsThenItemsThenRemovals() {
        UUID frame = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
        List<StoredEvent> events = List.of(
                entity(1, Action.ENTITY_SPAWN, "minecraft:item_frame", new Payload.EntityRef(frame)),
                kill(2, new Payload.Entity(new byte[] {1})),
                item(3, Action.CONTAINER_DEPOSIT, DIAMOND, 1, null),
                block(4, Action.BLOCK_BREAK, 0, 64, 0, CHEST, AIR),
                block(5, Action.BLOCK_BREAK, 0, 63, 0, STONE, AIR));

        List<PlannedChange> changes = planner.plan(RollbackMode.ROLLBACK, events, ALL).chunks().getFirst().changes();

        assertEquals(5, changes.size());
        assertEquals(setBlock(0, 63, 0, STONE), changes.get(0));
        assertEquals(setBlock(0, 64, 0, CHEST), changes.get(1));
        assertInstanceOf(PlannedChange.SpawnEntity.class, changes.get(2));
        assertArrayEquals(new byte[] {1}, ((PlannedChange.SpawnEntity) changes.get(2)).entity());
        assertInstanceOf(PlannedChange.AdjustItems.class, changes.get(3));
        assertEquals(new PlannedChange.RemoveEntity(position(0, 64, 0), frame), changes.get(4));
    }

    @Test
    public void aBuilderFedPageByPageEqualsOnePlan() {
        List<StoredEvent> events = List.of(
                block(1, Action.BLOCK_BREAK, 0, 64, 0, STONE, AIR),
                block(2, Action.BLOCK_PLACE, 0, 64, 0, DIRT, AIR),
                item(3, Action.CONTAINER_WITHDRAW, DIAMOND, 4, null));
        PlanBuilder builder = planner.builder(RollbackMode.ROLLBACK, ALL);
        builder.add(events.get(2));
        builder.add(events.get(0));
        builder.add(events.get(1));
        assertEquals(planner.plan(RollbackMode.ROLLBACK, events, ALL), builder.build());
    }

    private static StoredEvent block(long id, Action action, int x, int y, int z, String subject,
            @Nullable String secondary) {
        return block(id, action, x, y, z, subject, secondary, null);
    }

    private static StoredEvent block(long id, Action action, int x, int y, int z, String subject,
            @Nullable String secondary, @Nullable Payload payload) {
        return new StoredEvent(id, "server", "world", position(x, y, z), ALEX, action, subject, secondary, 0, false,
                payload == null ? null : PayloadCodec.encode(payload));
    }

    private static StoredEvent item(long id, Action action, String material, int amount, @Nullable Payload payload) {
        return new StoredEvent(id, "server", "world", position(0, 64, 0), ALEX, action, material, null, amount, false,
                payload == null ? null : PayloadCodec.encode(payload));
    }

    private static StoredEvent kill(long id, @Nullable Payload payload) {
        return entity(id, Action.ENTITY_KILL, "minecraft:zombie", payload);
    }

    private static StoredEvent entity(long id, Action action, String type, @Nullable Payload payload) {
        return new StoredEvent(id, "server", "world", position(0, 64, 0), ALEX, action, type, null, 0, false,
                payload == null ? null : PayloadCodec.encode(payload));
    }

    private static PlannedChange.SetBlock setBlock(int x, int y, int z, String state) {
        return new PlannedChange.SetBlock(position(x, y, z), state, SignPatch.NONE, List.of());
    }

    private static long position(int x, int y, int z) {
        return BlockPositions.pack(x, y, z);
    }

    private static String json(String text) {
        return "{\"text\":\"" + text + "\"}";
    }
}
