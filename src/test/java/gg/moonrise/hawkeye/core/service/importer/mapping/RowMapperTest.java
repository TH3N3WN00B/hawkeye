package gg.moonrise.hawkeye.core.service.importer.mapping;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.hawkeye.entity.event.PendingEvent;
import gg.moonrise.hawkeye.entity.importer.SkipReason;
import gg.moonrise.hawkeye.entity.importer.SourceNames;
import gg.moonrise.hawkeye.entity.importer.SourceRow;
import gg.moonrise.hawkeye.entity.importer.SourceTable;
import gg.moonrise.hawkeye.entity.importer.SourceUser;
import gg.moonrise.hawkeye.entity.payload.Payload;
import gg.moonrise.hawkeye.entity.payload.SignSides;
import gg.moonrise.hawkeye.util.BlockPositions;
import gg.moonrise.hawkeye.util.EventIds;
import gg.moonrise.hawkeye.util.codec.PayloadCodec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

public class RowMapperTest {

    private static final long T = 1_767_225_600L;
    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID ZOMBIE = UUID.fromString("00000000-0000-0000-0000-0000000000ee");
    private static final byte[] ITEM = {1, 2, 3};
    private static final String LONG = "x".repeat(80);

    private static final int USER = 1;
    private static final int TNT = 2;
    private static final int BLANK = 3;
    private static final int LONG_NAME = 4;
    private static final int STEVE = 5;
    private static final int WORLD = 1;

    private static final int STONE = 1;
    private static final int STAIRS = 2;
    private static final int SIGN = 3;
    private static final int PAINTING = 4;
    private static final int SWORD = 5;
    private static final int CHEST = 6;
    private static final int ARMOR_STAND = 7;
    private static final int EAST = 1;
    private static final int BOTTOM = 2;
    private static final int ZOMBIE_TYPE = 1;
    private static final int SHEEP = 2;

    private static final SourceNames NAMES = new SourceNames(
            Map.of(USER, new SourceUser("Alex", ALEX), TNT, new SourceUser("#tnt", null),
                    BLANK, new SourceUser(" ", ALEX), LONG_NAME, new SourceUser(LONG, ALEX),
                    STEVE, new SourceUser("Steve", null)),
            Map.of(WORLD, "world"),
            Map.of(STONE, "minecraft:stone", STAIRS, "minecraft:oak_stairs", SIGN, "minecraft:oak_sign",
                    PAINTING, "minecraft:painting", SWORD, "minecraft:diamond_sword", CHEST, "chest",
                    ARMOR_STAND, "minecraft:armor_stand"),
            Map.of(EAST, "facing=east", BOTTOM, "half=bottom"),
            Map.of(ZOMBIE_TYPE, "zombie", SHEEP, "sheep"));

    /** Marks every state it normalises, so tests see that the server's form is used. */
    private static final BlockStates MARKED = state -> state.equals(BlockRowMapper.AIR) ? state : state + "#";

    /** Decodes every non-empty blob to fixed bytes. */
    private static final ItemDecoder DECODER = new ItemDecoder() {
        @Override
        public @NotNull Optional<byte[]> decode(@NotNull String material, byte @NotNull [] metadata) {
            return metadata.length == 0 ? Optional.empty() : Optional.of(ITEM);
        }

        @Override
        public long failures() {
            return 0;
        }
    };

    private final RowMapper mapper = new RowMapper(NAMES, MARKED, DECODER, (T + 10_000) * 1000L);

    @Test
    public void blockBreaksPlacesAndInteractionsCarryTheFullState() {
        PendingEvent broken = event(block(STAIRS, new int[] {EAST, BOTTOM}, 0, 0));
        assertEquals(Action.BLOCK_BREAK, broken.action());
        assertEquals("minecraft:oak_stairs[facing=east,half=bottom]#", broken.subject());
        assertEquals("minecraft:air", broken.secondary());
        assertEquals(EventIds.stamp(T * 1000L, 0), broken.stamp());
        assertEquals("world", broken.world());
        assertEquals(BlockPositions.pack(10, 64, -20), broken.position());
        assertEquals(Actor.player(ALEX, "Alex"), broken.actor());
        assertNull(broken.data());
        assertFalse(broken.rolledBack());

        PendingEvent placed = event(block(STONE, new int[0], 1, 0));
        assertEquals(Action.BLOCK_PLACE, placed.action());
        assertEquals("minecraft:stone#", placed.subject());
        assertEquals("minecraft:air", placed.secondary());

        PendingEvent used = event(block(STAIRS, new int[] {EAST}, 2, 0));
        assertEquals(Action.INTERACT, used.action());
        assertEquals("minecraft:oak_stairs[facing=east]#", used.subject());
        assertEquals(used.subject(), used.secondary());
    }

    @Test
    public void blockStatesDropUnknownTokensAndGetANamespace() {
        assertEquals("minecraft:oak_stairs[half=bottom]#", event(block(STAIRS, new int[] {99, BOTTOM}, 0, 0))
                .subject());
        assertEquals("minecraft:stone#", event(block(STONE, new int[] {99}, 0, 0)).subject());
        assertEquals("minecraft:chest#", event(block(CHEST, new int[0], 1, 0)).subject());
    }

    @Test
    public void entitiesLoggedAsBlocksBecomeEntityEvents() {
        PendingEvent removed = event(block(PAINTING, new int[0], 0, 0));
        assertEquals(Action.ENTITY_REMOVE, removed.action());
        assertEquals("minecraft:painting", removed.subject());
        assertNull(removed.secondary());
        assertNull(removed.data());
        PendingEvent spawned = event(block(ARMOR_STAND, new int[0], 1, 0));
        assertEquals(Action.ENTITY_SPAWN, spawned.action());
        assertEquals("minecraft:armor_stand", spawned.subject());
        assertEquals(SkipReason.NO_EQUIVALENT, skipped(block(PAINTING, new int[0], 2, 0)));
    }

    @Test
    public void killsAndSpawnsNameTheirEntity() {
        PendingEvent kill = event(entityBlock(ZOMBIE_TYPE, 3, null, 0));
        assertEquals(Action.ENTITY_KILL, kill.action());
        assertEquals("minecraft:zombie", kill.subject());
        assertNull(kill.data());
        assertEquals("minecraft:player", event(entityBlock(0, 3, null, 0)).subject());
        assertEquals(SkipReason.UNKNOWN_REFERENCE, skipped(entityBlock(42, 3, null, 0)));

        PendingEvent spawn = event(entityBlock(SHEEP, 13, ZOMBIE, 0));
        assertEquals(Action.ENTITY_SPAWN, spawn.action());
        assertEquals("minecraft:sheep", spawn.subject());
        assertEquals(new Payload.EntityRef(ZOMBIE), payload(spawn));
        assertNull(event(entityBlock(SHEEP, 13, null, 0)).data());
        assertEquals(SkipReason.UNKNOWN_REFERENCE, skipped(entityBlock(42, 13, null, 0)));
    }

    @Test
    public void otherBlockActionsHaveNoEquivalent() {
        assertEquals(SkipReason.NO_EQUIVALENT, skipped(block(STONE, new int[0], 4, 0)));
        assertEquals(SkipReason.NO_EQUIVALENT, skipped(block(STONE, new int[0], 12, 0)));
    }

    @Test
    public void containerRowsMoveItemsWithTheirComponents() {
        PendingEvent taken = event(container(SourceTable.CONTAINER, SWORD, 0, ITEM, 0));
        assertEquals(Action.CONTAINER_WITHDRAW, taken.action());
        assertEquals("minecraft:diamond_sword", taken.subject());
        assertNull(taken.secondary());
        assertEquals(3, taken.amount());
        assertArrayEquals(ITEM, ((Payload.Item) payload(taken)).bytes());

        PendingEvent added = event(container(SourceTable.ENTITY_CONTAINER, SWORD, 1, null, 0));
        assertEquals(Action.CONTAINER_DEPOSIT, added.action());
        assertNull(added.data());
        assertEquals(SkipReason.NO_EQUIVALENT, skipped(container(SourceTable.CONTAINER, SWORD, 2, null, 0)));
        assertEquals(SkipReason.UNKNOWN_REFERENCE, skipped(container(SourceTable.CONTAINER, 99, 1, null, 0)));
    }

    @Test
    public void itemRowsMapByAction() {
        Map<Integer, Action> expected = Map.of(2, Action.ITEM_DROP, 3, Action.ITEM_PICKUP,
                4, Action.CONTAINER_WITHDRAW, 5, Action.CONTAINER_DEPOSIT, 6, Action.ITEM_THROW, 7, Action.ITEM_THROW,
                8, Action.ITEM_BREAK, 10, Action.ITEM_CRAFT, 12, Action.ITEM_TRADE);
        expected.forEach((code, action) -> {
            PendingEvent event = event(item(code, ITEM));
            assertEquals(action, event.action(), "item action " + code);
            assertEquals("minecraft:diamond_sword", event.subject());
            assertEquals(2, event.amount());
            assertArrayEquals(ITEM, ((Payload.Item) payload(event)).bytes());
            assertFalse(event.rolledBack());
        });
        for (int code : new int[] {0, 1, 9, 11, 13}) {
            assertEquals(SkipReason.NO_EQUIVALENT, skipped(item(code, null)), "item action " + code);
        }
    }

    @Test
    public void anEditCarriesTheBeforeSnapshotAndTheSignsBlock() {
        assertEquals(SkipReason.FOLDED, skipped(sign(3, List.of("old", "", "", "", "", "", "", ""), 0, T + 1)));
        mapper.map(block(SIGN, new int[] {EAST}, 1, 0, T, 5, 64, 5));
        assertEquals(SkipReason.FOLDED, skipped(sign(3, List.of("before", "", "", "", "", "", "", ""), 0, T + 1)));
        PendingEvent edit = event(sign(2, List.of("after", "", "", "", "b", "", "", ""), 0, T + 2));
        assertEquals(Action.SIGN_EDIT, edit.action());
        assertEquals("minecraft:oak_sign[facing=east]#", edit.subject());
        assertEquals(edit.subject(), edit.secondary());
        Payload.Sign text = (Payload.Sign) payload(edit);
        assertEquals(LegacySignText.sides(List.of("before", "", "", "", "", "", "", "")), text.before());
        assertEquals(LegacySignText.sides(List.of("after", "", "", "", "b", "", "", "")), text.after());

        PendingEvent placed = event(sign(1, List.of("hi", "", "", "", "", "", "", ""), 0, T + 3));
        assertEquals(SignSides.EMPTY, ((Payload.Sign) payload(placed)).before(), "the snapshot was taken once");
    }

    @Test
    public void aSignWhoseBlockWasNeverSeenIsAnOakSign() {
        PendingEvent edit = event(new SourceRow.Sign(1, T, USER, WORLD, 99, 70, 99, 2, 0,
                List.of("a", "", "", "", "", "", "", ""), 0));
        assertEquals("minecraft:oak_sign#", edit.subject());
    }

    @Test
    public void aSignBlockIsRememberedEvenWhenItsRowIsTooOld() {
        assertEquals(SkipReason.BEFORE_EPOCH, mapper.map(block(SIGN, new int[] {EAST}, 1, 0, 1_600_000_000L, 5, 64, 5))
                instanceof MappedRow.Skipped skipped ? skipped.reason() : null);
        assertEquals("minecraft:oak_sign[facing=east]#", event(sign(2, List.of("a", "", "", "", "", "", "", ""), 0,
                T + 2)).subject());
    }

    @Test
    public void aBrokenSignsTextGoesWithItsBlockBreak() {
        mapper.map(block(SIGN, new int[0], 1, 0, T, 5, 64, 5));
        assertEquals(SkipReason.FOLDED, skipped(sign(0, List.of("gone", "", "", "", "", "", "", ""), 0, T - 5)));
        PendingEvent broken = event(block(SIGN, new int[0], 0, 0, T, 5, 64, 5));
        assertEquals(Action.BLOCK_BREAK, broken.action());
        Payload.Sign text = (Payload.Sign) payload(broken);
        assertEquals(LegacySignText.sides(List.of("gone", "", "", "", "", "", "", "")), text.before());
        assertEquals(SignSides.EMPTY, text.after());
        assertNull(event(block(SIGN, new int[0], 0, 0, T, 5, 64, 5)).data(), "the text was used once");
        assertEquals(SkipReason.NO_EQUIVALENT, skipped(sign(4, List.of("", "", "", "", "", "", "", ""), 0, T)));
    }

    @Test
    public void chatCommandsAndSessions() {
        PendingEvent chat = event(new SourceRow.Message(SourceTable.CHAT, 1, T, USER, WORLD, 1, 2, 3, "hello"));
        assertEquals(Action.CHAT, chat.action());
        assertEquals("", chat.subject());
        assertEquals(new Payload.Text("hello"), payload(chat));
        PendingEvent command = event(new SourceRow.Message(SourceTable.COMMAND, 1, T, USER, WORLD, 1, 2, 3, "/spawn"));
        assertEquals(Action.COMMAND, command.action());
        assertEquals(new Payload.Text("/spawn"), payload(command));
        assertEquals(Action.JOIN, event(new SourceRow.Session(1, T, USER, WORLD, 1, 2, 3, 1)).action());
        assertEquals(Action.QUIT, event(new SourceRow.Session(1, T, USER, WORLD, 1, 2, 3, 0)).action());
        assertEquals(SkipReason.NO_EQUIVALENT, skipped(new SourceRow.Session(1, T, USER, WORLD, 1, 2, 3, 2)));
    }

    @Test
    public void entityInteractions() {
        Map<Integer, Action> expected = Map.of(1, Action.ENTITY_SHEAR, 2, Action.ENTITY_LEASH, 3, Action.ENTITY_UNLEASH);
        expected.forEach((code, action) -> {
            PendingEvent event = event(new SourceRow.EntityInteraction(1, T, USER, WORLD, 1, 2, 3, SHEEP, code));
            assertEquals(action, event.action());
            assertEquals("minecraft:sheep", event.subject());
        });
        assertEquals(SkipReason.NO_EQUIVALENT, skipped(new SourceRow.EntityInteraction(1, T, USER, WORLD, 1, 2, 3,
                SHEEP, 0)));
        assertEquals(SkipReason.UNKNOWN_REFERENCE, skipped(new SourceRow.EntityInteraction(1, T, USER, WORLD, 1, 2,
                3, 42, 1)));
    }

    @Test
    public void rolledBackFlagsFollowBitOneOfReversibleActions() {
        assertTrue(event(block(STONE, new int[0], 0, 1)).rolledBack());
        assertTrue(event(block(STONE, new int[0], 1, 3)).rolledBack());
        assertFalse(event(block(STONE, new int[0], 1, 2)).rolledBack());
        assertFalse(event(block(STONE, new int[0], 2, 1)).rolledBack(), "an interaction is never undone");
        assertTrue(event(entityBlock(ZOMBIE_TYPE, 3, null, 1)).rolledBack());
        assertTrue(event(container(SourceTable.CONTAINER, SWORD, 1, null, 1)).rolledBack());
        assertTrue(event(container(SourceTable.ENTITY_CONTAINER, SWORD, 0, null, 3)).rolledBack());
        assertTrue(event(sign(2, List.of("a", "", "", "", "", "", "", ""), 1, T)).rolledBack());
        assertFalse(event(sign(2, List.of("a", "", "", "", "", "", "", ""), 0, T)).rolledBack());
    }

    @Test
    public void rowsOutsideHawkeyesTimeAreSkipped() {
        long epoch = EventIds.EPOCH_MILLIS / 1000;
        assertEquals(SkipReason.BEFORE_EPOCH, skipped(block(STONE, new int[0], 0, 0, epoch - 1, 0, 64, 0)));
        assertEquals(Action.BLOCK_BREAK, event(block(STONE, new int[0], 0, 0, epoch, 0, 64, 0)).action());
        assertEquals(SkipReason.BEFORE_EPOCH, skipped(block(STONE, new int[0], 0, 0, T + 10_001, 0, 64, 0)));
        assertEquals(Action.BLOCK_BREAK, event(block(STONE, new int[0], 0, 0, T + 10_000, 0, 64, 0)).action());
        assertEquals(SkipReason.BEFORE_EPOCH, skipped(new SourceRow.Message(SourceTable.CHAT, 1, 0, USER, WORLD, 0, 0,
                0, "")));
    }

    @Test
    public void unresolvableRowsAreUnknownReferences() {
        assertEquals(SkipReason.UNKNOWN_REFERENCE, skipped(new SourceRow.Session(1, T, USER, 9, 0, 0, 0, 1)));
        assertEquals(SkipReason.UNKNOWN_REFERENCE, skipped(new SourceRow.Session(1, T, USER, SourceRow.NO_WORLD, 0, 0,
                0, 1)));
        assertEquals(SkipReason.UNKNOWN_REFERENCE, skipped(new SourceRow.Session(1, T, USER, WORLD, 0, 5000, 0, 1)));
        assertEquals(SkipReason.UNKNOWN_REFERENCE, skipped(new SourceRow.Session(1, T, USER, WORLD,
                BlockPositions.MAX_HORIZONTAL + 1, 0, 0, 1)));
        assertEquals(SkipReason.UNKNOWN_REFERENCE, skipped(block(99, new int[0], 0, 0)));
    }

    @Test
    public void actorsArePlayersCausesOrUnknown() {
        assertEquals(Actor.player(ALEX, "Alex"), actor(USER));
        assertEquals(Actor.source("#tnt"), actor(TNT));
        assertEquals("#tnt", actor(TNT).name());
        assertEquals(Actor.source("#steve"), actor(STEVE));
        assertEquals(Actor.source(SourceNameResolver.UNKNOWN_ACTOR), actor(BLANK));
        assertEquals(Actor.source(SourceNameResolver.UNKNOWN_ACTOR), actor(77));
        assertEquals(Actor.player(ALEX, "x".repeat(Actor.MAX_LENGTH)), actor(LONG_NAME));
    }

    @Test
    public void knowsSaysWhenTheDictionariesLackAnId() {
        assertTrue(mapper.knows(block(STAIRS, new int[] {EAST}, 0, 0)));
        assertFalse(mapper.knows(block(STAIRS, new int[] {99}, 0, 0)));
        assertFalse(mapper.knows(block(99, new int[0], 0, 0)));
        assertFalse(mapper.knows(new SourceRow.Session(1, T, 77, WORLD, 0, 0, 0, 1)));
        assertFalse(mapper.knows(new SourceRow.Session(1, T, USER, 9, 0, 0, 0, 1)));
        assertTrue(mapper.knows(new SourceRow.Session(1, T, USER, SourceRow.NO_WORLD, 0, 0, 0, 1)));
        assertTrue(mapper.knows(entityBlock(0, 3, null, 0)));
        assertFalse(mapper.knows(entityBlock(42, 13, null, 0)));
        assertFalse(mapper.knows(new SourceRow.EntityInteraction(1, T, USER, WORLD, 1, 2, 3, 42, 1)));

        mapper.names(new SourceNames(NAMES.users(), Map.of(WORLD, "world", 9, "world_nether"), NAMES.materials(),
                NAMES.blockData(), NAMES.entities()));
        assertTrue(mapper.knows(new SourceRow.Session(1, T, USER, 9, 0, 0, 0, 1)));
        assertEquals("world_nether", event(new SourceRow.Session(1, T, USER, 9, 0, 0, 0, 1)).world());
    }

    private @NotNull Actor actor(int user) {
        return event(new SourceRow.Session(1, T, user, WORLD, 0, 0, 0, 1)).actor();
    }

    private @NotNull PendingEvent event(@NotNull SourceRow row) {
        MappedRow mapped = mapper.map(row);
        if (mapped instanceof MappedRow.Mapped event) {
            return event.event();
        }
        throw new AssertionError("expected an event, got " + mapped);
    }

    private @NotNull SkipReason skipped(@NotNull SourceRow row) {
        MappedRow mapped = mapper.map(row);
        if (mapped instanceof MappedRow.Skipped skipped) {
            return skipped.reason();
        }
        throw new AssertionError("expected a skip, got " + mapped);
    }

    private static @NotNull Payload payload(@NotNull PendingEvent event) {
        List<Payload> found = new ArrayList<>();
        if (event.data() != null) {
            found.add(PayloadCodec.decode(event.data()));
        }
        assertEquals(1, found.size(), "one payload");
        return found.getFirst();
    }

    private static SourceRow.Block block(int type, int[] blockData, int action, int rolledBack) {
        return block(type, blockData, action, rolledBack, T, 10, 64, -20);
    }

    private static SourceRow.Block block(int type, int[] blockData, int action, int rolledBack, long time, int x,
            int y, int z) {
        return new SourceRow.Block(1, time, USER, WORLD, x, y, z, type, 0, blockData, action, rolledBack, null);
    }

    private static SourceRow.Block entityBlock(int type, int action, UUID entity, int rolledBack) {
        return new SourceRow.Block(1, T, USER, WORLD, 10, 64, -20, type, 7, new int[0], action, rolledBack, entity);
    }

    private static SourceRow.Container container(SourceTable table, int type, int action, byte[] metadata,
            int rolledBack) {
        return new SourceRow.Container(table, 1, T, USER, WORLD, 10, 64, -20, type, 3, metadata, action, rolledBack);
    }

    private static SourceRow.Item item(int action, byte[] metadata) {
        return new SourceRow.Item(1, T, USER, WORLD, 10, 64, -20, SWORD, 2, metadata, action);
    }

    private static SourceRow.Sign sign(int action, List<String> lines, int rolledBack, long time) {
        return new SourceRow.Sign(1, time, USER, WORLD, 5, 64, 5, action, 0, lines, rolledBack);
    }
}
