package gg.moonrise.hawkeye.core.service.importer;

import static gg.moonrise.hawkeye.core.repository.coreprotect.source.CoreProtectFixture.at;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.core.repository.coreprotect.source.CoreProtectFixture;
import gg.moonrise.hawkeye.core.service.importer.mapping.sign.LegacySignText;
import gg.moonrise.hawkeye.core.storage.event.EventStore;
import gg.moonrise.hawkeye.core.storage.shard.StorageState;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.entity.importer.SkipReason;
import gg.moonrise.hawkeye.entity.payload.Payload;
import gg.moonrise.hawkeye.entity.payload.SignSides;
import gg.moonrise.hawkeye.entity.query.Area;
import gg.moonrise.hawkeye.entity.query.LookupQuery;
import gg.moonrise.hawkeye.entity.query.ShardScope;
import gg.moonrise.hawkeye.support.TestPlugins;
import gg.moonrise.hawkeye.util.position.BlockPositions;
import gg.moonrise.hawkeye.util.serialize.ItemBytes;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One CoreProtect history for the import tests on every engine: {@link #populate} writes a row of every
 * kind (plus rows to skip for each reason) and {@link #verify} checks what the import made of them. Needs
 * MockBukkit (the item blob is real item meta). Every row has a second of its own, so each event's id is
 * exactly its row's time.
 */
public final class ImportScenario {

    /** The first event second: 2026-01-01T00:00:00Z. */
    public static final long T = 1_767_225_600L;

    /** The player of the scenario. */
    public static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    /** The zombie the scenario spawns. */
    public static final UUID ZOMBIE = UUID.fromString("00000000-0000-0000-0000-0000000000ee");

    /** Events the scenario imports. */
    public static final int EVENTS = 15;

    /** Rows the scenario skips, by reason. */
    public static final Map<SkipReason, Long> SKIPPED = Map.of(SkipReason.BEFORE_EPOCH, 2L,
            SkipReason.NO_EQUIVALENT, 1L, SkipReason.FOLDED, 2L, SkipReason.UNKNOWN_REFERENCE, 1L);

    /** Where the scenario's sign stands. */
    public static final int SIGN_X = 7;

    private ImportScenario() {
    }

    /**
     * Writes the scenario.
     *
     * @param db      an empty fixture with the current layout
     * @param version the CoreProtect version of the install row, which makes the fingerprint
     * @throws SQLException when writing fails
     */
    public static void populate(@NotNull CoreProtectFixture db, @NotNull String version) throws SQLException {
        db.version(T - 100, version);
        int alex = db.user("Alex", ALEX.toString());
        int tnt = db.user("#tnt", null);
        int world = db.world("world");
        int stone = db.material("minecraft:stone");
        int stairs = db.material("minecraft:oak_stairs");
        int sign = db.material("minecraft:oak_sign");
        int painting = db.material("minecraft:painting");
        int sword = db.material("minecraft:diamond_sword");
        int stick = db.material("minecraft:stick");
        int east = db.blockData("facing=east");
        int bottom = db.blockData("half=bottom");
        int rotation = db.blockData("rotation=4");
        int zombie = db.entity("zombie");
        int sheep = db.entity("sheep");
        long spawn = db.entitySpawn(ZOMBIE.toString());

        db.block(1_600_000_000L, alex, at(world, 0, 64, 0), stone, 0, null, 1, 0);
        db.chat(1_600_000_001L, alex, at(world, 0, 64, 0), "long ago");
        db.block(T + 1, alex, at(world, 1, 64, 1), stairs, 0, east + "," + bottom, 1, 0);
        db.block(T + 2, tnt, at(world, 2, 64, 2), stone, 0, null, 0, 1);
        db.block(T + 3, alex, at(world, SIGN_X, 64, 7), sign, 0, String.valueOf(rotation), 1, 0);
        db.sign(T + 4, alex, at(world, SIGN_X, 64, 7), 3, 0, List.of("Old"), 0);
        db.sign(T + 5, alex, at(world, SIGN_X, 64, 7), 2, 0, List.of("§cHello", "", "", "", "Back"), 0);
        db.sign(T + 6, alex, at(world, SIGN_X, 64, 7), 0, 0, List.of("§cHello", "", "", "", "Back"), 0);
        db.block(T + 7, alex, at(world, 3, 64, 3), painting, 0, null, 0, 0);
        db.block(T + 8, alex, at(world, 4, 64, 4), zombie, 0, null, 3, 0);
        db.block(T + 9, alex, at(world, 5, 64, 5), zombie, spawn, null, 13, 0);
        db.block(T + 11, alex, at(world, SIGN_X, 64, 7), sign, 0, String.valueOf(rotation), 0, 0);
        db.container(T + 12, alex, at(world, 6, 64, 6), sword, 1, metadata(sword()), 1, 0);
        db.container(T + 13, alex, at(world, 6, 64, 6), stick, 5, null, 0, 0);
        db.item(T + 14, alex, at(world, 6, 65, 6), stick, 3, null, 2);
        db.item(T + 15, alex, at(world, 6, 65, 6), stick, 1, null, 9);
        db.chat(T + 16, alex, at(world, 0, 70, 0), "hello world");
        db.command(T + 17, alex, at(world, 0, 70, 0), "/home");
        db.session(T + 18, alex, at(world, 0, 70, 0), 1);
        db.entityInteraction(T + 19, alex, at(world, 8, 64, 8), sheep, 1);
        db.block(T + 20, alex, at(world, 9, 64, 9), 999, 0, null, 1, 0);
    }

    /**
     * Checks the imported events.
     *
     * @param events the events of the import node, oldest first
     */
    public static void verify(@NotNull List<StoredEvent> events) {
        assertEquals(EVENTS, events.size(), events.toString());
        Actor alex = Actor.player(ALEX, "Alex");
        // The server's form, with every property (MockBukkit fills the defaults as Paper does).
        String stairs = "minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]";
        String sign = "minecraft:oak_sign[rotation=4,waterlogged=false]";

        StoredEvent placed = one(events, T + 1);
        assertEvent(placed, Action.BLOCK_PLACE, stairs, "minecraft:air", 1, 64, 1, alex);
        assertFalse(placed.rolledBack());
        StoredEvent broken = one(events, T + 2);
        assertEvent(broken, Action.BLOCK_BREAK, "minecraft:stone", "minecraft:air",
                2, 64, 2, Actor.source("#tnt"));
        assertTrue(broken.rolledBack(), "the source had rolled it back");
        assertEvent(one(events, T + 3), Action.BLOCK_PLACE, sign, "minecraft:air", SIGN_X, 64, 7, alex);

        StoredEvent edit = one(events, T + 5);
        assertEvent(edit, Action.SIGN_EDIT, sign, sign, SIGN_X, 64, 7, alex);
        Payload.Sign text = (Payload.Sign) edit.payload().orElseThrow();
        SignSides hello = LegacySignText.sides(List.of("§cHello", "", "", "", "Back", "", "", ""));
        assertEquals(LegacySignText.sides(List.of("Old", "", "", "", "", "", "", "")), text.before());
        assertEquals(hello, text.after());

        assertEvent(one(events, T + 7), Action.ENTITY_REMOVE, "minecraft:painting", null, 3, 64, 3, alex);
        assertEvent(one(events, T + 8), Action.ENTITY_KILL, "minecraft:zombie", null, 4, 64, 4, alex);
        assertNull(one(events, T + 8).data(), "a kill carries no entity");
        StoredEvent spawned = one(events, T + 9);
        assertEvent(spawned, Action.ENTITY_SPAWN, "minecraft:zombie", null, 5, 64, 5, alex);
        assertEquals(new Payload.EntityRef(ZOMBIE), spawned.payload().orElseThrow());

        StoredEvent signBroken = one(events, T + 11);
        assertEvent(signBroken, Action.BLOCK_BREAK, sign, "minecraft:air", SIGN_X, 64, 7, alex);
        assertEquals(new Payload.Sign(hello, SignSides.EMPTY), signBroken.payload().orElseThrow());

        StoredEvent deposit = one(events, T + 12);
        assertEvent(deposit, Action.CONTAINER_DEPOSIT, "minecraft:diamond_sword", null, 6, 64, 6, alex);
        assertEquals(1, deposit.amount());
        ItemStack item = ItemBytes.deserialize(((Payload.Item) deposit.payload().orElseThrow()).bytes());
        assertEquals(Component.text("Excalibur"), item.getItemMeta().displayName());
        StoredEvent withdraw = one(events, T + 13);
        assertEvent(withdraw, Action.CONTAINER_WITHDRAW, "minecraft:stick", null, 6, 64, 6, alex);
        assertEquals(5, withdraw.amount());
        assertNull(withdraw.data());
        StoredEvent drop = one(events, T + 14);
        assertEvent(drop, Action.ITEM_DROP, "minecraft:stick", null, 6, 65, 6, alex);
        assertEquals(3, drop.amount());

        assertEvent(one(events, T + 16), Action.CHAT, "", null, 0, 70, 0, alex);
        assertEquals(new Payload.Text("hello world"), one(events, T + 16).payload().orElseThrow());
        assertEquals(new Payload.Text("/home"), one(events, T + 17).payload().orElseThrow());
        assertEquals(Action.COMMAND, one(events, T + 17).action());
        assertEvent(one(events, T + 18), Action.JOIN, "", null, 0, 70, 0, alex);
        assertEvent(one(events, T + 19), Action.ENTITY_SHEAR, "minecraft:sheep", null, 8, 64, 8, alex);
    }

    /**
     * Every event of the home shard, oldest first.
     *
     * @return the events
     * @throws SQLException when reading fails
     */
    public static @NotNull List<StoredEvent> all() throws SQLException {
        StorageState.Ready ready = TestPlugins.awaitStorage();
        return TestPlugins.bean(EventStore.class).find(ready.home(), LookupQuery.at(new Area.Everywhere()),
                new ShardScope.All(), 0, 100_000, false);
    }

    /**
     * The events of one server or import node, oldest first.
     *
     * @param server the node's name
     * @return the events
     * @throws SQLException when reading fails
     */
    public static @NotNull List<StoredEvent> of(@NotNull String server) throws SQLException {
        return all().stream().filter(event -> event.server().equals(server)).toList();
    }

    /**
     * What an event is, for comparing two imports: everything but the node.
     *
     * @param event the event
     * @return a description
     */
    public static @NotNull String describe(@NotNull StoredEvent event) {
        return event.epochMillis() + " " + event.world() + " " + event.position() + " " + event.actor() + " "
                + event.action() + " " + event.subject() + " " + event.secondary() + " " + event.amount() + " "
                + event.rolledBack() + " " + Arrays.toString(event.data());
    }

    /**
     * A CoreProtect item metadata blob, written the way CoreProtect writes one for an item without extras.
     *
     * @param item the item
     * @return the blob
     */
    @SuppressWarnings("deprecation")
    public static byte @NotNull [] metadata(@NotNull ItemStack item) {
        List<List<Map<String, Object>>> lists = new ArrayList<>();
        lists.add(new ArrayList<>(List.of(new HashMap<>(item.getItemMeta().serialize()))));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (BukkitObjectOutputStream out = new BukkitObjectOutputStream(bytes)) {
            out.writeObject(lists);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /**
     * The skip counts of a progress.
     *
     * @param progress the progress
     * @return every reason with a count
     */
    public static @NotNull Map<SkipReason, Long> skipped(@NotNull ImportProgress progress) {
        Map<SkipReason, Long> counts = new EnumMap<>(SkipReason.class);
        for (SkipReason reason : SkipReason.values()) {
            if (progress.skipped(reason) > 0) {
                counts.put(reason, progress.skipped(reason));
            }
        }
        return counts;
    }

    private static @NotNull ItemStack sword() {
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta meta = sword.getItemMeta();
        meta.displayName(Component.text("Excalibur"));
        sword.setItemMeta(meta);
        return sword;
    }

    private static @NotNull StoredEvent one(@NotNull List<StoredEvent> events, long second) {
        Predicate<StoredEvent> at = event -> event.epochMillis() == second * 1000L;
        List<StoredEvent> found = events.stream().filter(at).toList();
        assertEquals(1, found.size(), "one event at " + second + " in " + events);
        return found.getFirst();
    }

    private static void assertEvent(@NotNull StoredEvent event, @NotNull Action action, @NotNull String subject,
            @Nullable String secondary, int x, int y, int z, @NotNull Actor actor) {
        assertEquals(action, event.action(), event.toString());
        assertEquals(subject, event.subject(), event.toString());
        assertEquals(secondary, event.secondary(), event.toString());
        assertEquals("world", event.world());
        assertEquals(BlockPositions.pack(x, y, z), event.position(), event.toString());
        assertEquals(actor, event.actor(), event.toString());
    }
}
