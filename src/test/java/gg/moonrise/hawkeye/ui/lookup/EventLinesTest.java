package gg.moonrise.hawkeye.ui.lookup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.config.message.ActionMessages;
import gg.moonrise.hawkeye.config.message.LookupMessages;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.entity.payload.Payload;
import gg.moonrise.hawkeye.entity.payload.SignSides;
import gg.moonrise.hawkeye.util.position.BlockPositions;
import gg.moonrise.hawkeye.util.event.EventIds;
import gg.moonrise.hawkeye.util.codec.PayloadCodec;
import gg.moonrise.hawkeye.util.nbt.NbtTag;
import gg.moonrise.hawkeye.util.serialize.ItemDetailsTest;
import gg.moonrise.hawkeye.util.serialize.ItemSummaryTest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

public class EventLinesTest {

    private static final Instant NOW = Instant.parse("2026-11-16T12:00:00Z");
    private static final Actor ALEX = Actor.player(UUID.fromString("00000000-0000-0000-0000-00000000a1e5"), "Alex");

    private final List<Book> opened = new ArrayList<>();
    private final EventLines lines = new EventLines(new LookupMessages(), new ActionMessages(), "survival", NOW,
            ZoneOffset.UTC, book -> {
                opened.add(book);
                return ClickEvent.runCommand("/read " + opened.size());
            });

    @Test
    public void shortNamesDropTheMinecraftNamespaceAndTheState() {
        assertEquals("oak_stairs", EventLines.shortName("minecraft:oak_stairs[facing=east,half=bottom]"));
        assertEquals("diamond", EventLines.shortName("minecraft:diamond"));
        assertEquals("zombie", EventLines.shortName("minecraft:zombie"));
        assertEquals("mymod:crate", EventLines.shortName("mymod:crate[open=true]"));
        assertEquals("", EventLines.shortName(""));
    }

    @Test
    public void theTargetHoverShowsTheFullState() {
        Component target = EventLines.target("minecraft:oak_stairs[facing=east]");
        assertEquals("oak_stairs", plain(target));
        HoverEvent<?> hover = target.hoverEvent();
        assertTrue(hover != null && hover.value() instanceof Component, String.valueOf(hover));
        assertEquals("minecraft:oak_stairs[facing=east]", plain((Component) hover.value()));
        assertEquals(Component.empty(), EventLines.target(""));
    }

    @Test
    public void everyActionReadsFromItsTemplate() {
        assertEquals("broke stone", plain(lines.action(event(Action.BLOCK_BREAK, "minecraft:stone", 0, null))));
        assertEquals("placed oak_stairs", plain(lines.action(event(Action.BLOCK_PLACE,
                "minecraft:oak_stairs[facing=east]", 0, null))));
        assertEquals("added 5x diamond", plain(lines.action(event(Action.CONTAINER_DEPOSIT, "minecraft:diamond", 5,
                null))));
        assertEquals("removed 2x iron_ingot", plain(lines.action(event(Action.CONTAINER_WITHDRAW,
                "minecraft:iron_ingot", 2, null))));
        assertEquals("killed zombie", plain(lines.action(event(Action.ENTITY_KILL, "minecraft:zombie", 0, null))));
        assertEquals("said hello <b>there</b>", plain(lines.action(event(Action.CHAT, "", 0,
                new Payload.Text("hello <b>there</b>")))));
        assertEquals("ran /spawn", plain(lines.action(event(Action.COMMAND, "", 0, new Payload.Text("/spawn")))));
        assertEquals("joined", plain(lines.action(event(Action.JOIN, "", 0, null))));
        assertEquals("left ", plain(lines.action(event(Action.QUIT, "", 0, null))));
        assertEquals("left kicked: AFK", plain(lines.action(event(Action.QUIT, "", 0,
                new Payload.Text("kicked: AFK")))));
    }

    @Test
    public void aVirtualContainerLineNamesTheInventory() {
        assertEquals("added 5x diamond to ender_chest", plain(lines.action(event(Action.VIRTUAL_DEPOSIT,
                "minecraft:diamond", 5, new Payload.VirtualItem("ender_chest", new byte[0])))));
        assertEquals("removed 2x iron_ingot from Vault #1", plain(lines.action(event(Action.VIRTUAL_WITHDRAW,
                "minecraft:iron_ingot", 2, new Payload.VirtualItem("Vault #1", new byte[] {10, 0, 0})))));
        assertEquals("Vault #1", plain(EventLines.text(event(Action.VIRTUAL_WITHDRAW, "minecraft:iron_ingot", 2,
                new Payload.VirtualItem("Vault #1", new byte[0]))).orElseThrow()));
    }

    @Test
    public void aDroppedBookShowsItsTitleAndOpensWhenClicked() {
        byte[] bytes = ItemDetailsTest.item("minecraft:written_book", ItemDetailsTest.compound(
                "minecraft:written_book_content", ItemDetailsTest.compound(
                        "pages", ItemDetailsTest.list(NbtTag.STRING, ItemDetailsTest.str("Day one"),
                                ItemDetailsTest.str("Day two")),
                        "title", ItemDetailsTest.compound("raw", ItemDetailsTest.str("Diary")),
                        "author", ItemDetailsTest.str("Alex"))));
        Component text = lines.action(event(Action.ITEM_DROP, "minecraft:written_book", 1, new Payload.Item(bytes)));
        assertEquals("dropped 1x written_book", plain(text));
        assertEquals(List.of("minecraft:written_book\nDiary by Alex, 2 pages\nClick to read"), hovers(text));
        assertEquals(List.of("/read 1"), commands(text));
        Book book = opened.getFirst();
        assertEquals("Diary", plain(book.title()));
        assertEquals("Alex", plain(book.author()));
        assertEquals(List.of("Day one", "Day two"), book.pages().stream().map(EventLinesTest::plain).toList());
    }

    @Test
    public void aBookAndQuillInAVirtualContainerOpensToo() {
        byte[] bytes = ItemDetailsTest.item("minecraft:writable_book", ItemDetailsTest.compound(
                "minecraft:writable_book_content", ItemDetailsTest.compound("pages", ItemDetailsTest.list(
                        NbtTag.COMPOUND, ItemDetailsTest.compound("raw", ItemDetailsTest.str("todo"))))));
        Component text = lines.action(event(Action.VIRTUAL_DEPOSIT, "minecraft:writable_book", 1,
                new Payload.VirtualItem("ender_chest", bytes)));
        assertEquals("added 1x writable_book to ender_chest", plain(text));
        assertEquals(List.of("minecraft:writable_book\nBook and quill, 1 pages\nClick to read"), hovers(text));
        assertEquals(List.of("todo"), opened.getFirst().pages().stream().map(EventLinesTest::plain).toList());
        assertEquals("", plain(opened.getFirst().title()));
    }

    @Test
    public void aBookWithoutPagesIsNotClickable() {
        byte[] bytes = ItemDetailsTest.item("minecraft:writable_book", ItemDetailsTest.compound(
                "minecraft:writable_book_content", ItemDetailsTest.compound()));
        Component text = lines.action(event(Action.ITEM_PICKUP, "minecraft:writable_book", 1,
                new Payload.Item(bytes)));
        assertEquals(List.of("minecraft:writable_book\nBook and quill, 0 pages"), hovers(text));
        assertTrue(commands(text).isEmpty());
        assertTrue(opened.isEmpty());
    }

    @Test
    public void aNamedEnchantedSwordShowsItsNameAndEnchantments() {
        byte[] bytes = ItemDetailsTest.item("minecraft:diamond_sword", ItemDetailsTest.compound(
                "minecraft:custom_name", ItemDetailsTest.compound("text", ItemDetailsTest.str("Excalibur")),
                "minecraft:enchantments", ItemDetailsTest.compound(
                        "minecraft:sharpness", new NbtTag.NbtInt(5),
                        "minecraft:unbreaking", new NbtTag.NbtInt(3))));
        Component text = lines.action(event(Action.CONTAINER_DEPOSIT, "minecraft:diamond_sword", 1,
                new Payload.Item(bytes)));
        assertEquals("added 1x diamond_sword", plain(text));
        assertEquals(List.of("minecraft:diamond_sword\nName: Excalibur\nsharpness 5\nunbreaking 3"), hovers(text));
        assertTrue(commands(text).isEmpty());
    }

    @Test
    public void aPlainItemKeepsTheIdHover() {
        assertEquals(List.of("minecraft:diamond"), hovers(lines.action(event(Action.ITEM_DROP, "minecraft:diamond",
                3, null))));
        assertEquals(List.of("minecraft:diamond"), hovers(lines.action(event(Action.VIRTUAL_DEPOSIT,
                "minecraft:diamond", 3, new Payload.VirtualItem("ender_chest", new byte[0])))));
        assertEquals(List.of("minecraft:stone"), hovers(lines.action(event(Action.ITEM_DROP, "minecraft:stone", 64,
                new Payload.Item(ItemSummaryTest.item("minecraft:stone", 1))))));
        assertEquals(List.of("minecraft:stone"), hovers(lines.action(event(Action.ITEM_DROP, "minecraft:stone", 1,
                new Payload.Item(new byte[] {(byte) 0xac, (byte) 0xed, 0, 5})))));
        assertTrue(opened.isEmpty());
    }

    @Test
    public void theServerBookClickIsACallback() {
        ClickEvent<?> click = EventLines.CALLBACK_BOOKS.open(Book.book(Component.text("t"), Component.text("a"),
                List.of(Component.text("page"))));
        assertTrue(click != null, "a click event without a platform callback provider too");
    }

    @Test
    public void aSignEditShowsTheEditedSideAfterTheEdit() {
        SignSides before = new SignSides(List.of(json("old"), json("")), List.of(json("back")));
        SignSides after = new SignSides(List.of(json("Hello"), json("World")), List.of(json("back")));
        Component text = lines.action(event(Action.SIGN_EDIT, "minecraft:oak_sign", 0, new Payload.Sign(before,
                after)));
        assertEquals("wrote Hello World", plain(text));
    }

    @Test
    public void aSnapshotShowsItsItemCountWithTheItemsInItsHover() {
        Payload.Snapshot snapshot = new Payload.Snapshot("ender_pearl to world_nether 10 64 -5", List.of(
                new Payload.Slot(36, ItemSummaryTest.item("minecraft:diamond_helmet", 1)),
                new Payload.Slot(0, ItemSummaryTest.item("minecraft:stone", 64)),
                new Payload.Slot(5, new byte[] {(byte) 0xac, (byte) 0xed, 0, 5}),
                new Payload.Slot(Payload.Snapshot.CURSOR, ItemSummaryTest.item("minecraft:torch", 3))));
        Component text = lines.action(event(Action.SNAPSHOT_TELEPORT, "", 70, snapshot));
        assertEquals("inventory at teleport (ender_pearl to world_nether 10 64 -5): 70 items [4 stacks]",
                plain(text));
        assertEquals(List.of("64x stone\n1x diamond_helmet\n3x torch"), hovers(text));
    }

    @Test
    public void aSnapshotWithoutItemsSaysSoInItsHover() {
        Component join = lines.action(event(Action.SNAPSHOT_JOIN, "", 0, new Payload.Snapshot("", List.of())));
        assertEquals("inventory at join: 0 items [0 stacks]", plain(join));
        assertEquals(List.of("empty"), hovers(join));
        Component death = lines.action(event(Action.SNAPSHOT_DEATH, "", 2, new Payload.Snapshot(
                "Alex fell from a high place", List.of(new Payload.Slot(1, new byte[] {9})))));
        assertEquals("inventory at death: 2 items [1 stacks] Alex fell from a high place", plain(death));
        assertTrue(hovers(death).isEmpty(), hovers(death).toString());
        assertEquals("inventory at quit: 0 items [0 stacks]", plain(lines.action(event(Action.SNAPSHOT_QUIT, "", 0,
                new Payload.Snapshot("", List.of())))));
    }

    @Test
    public void aLineShowsAgeActorActionAndLocation() {
        StoredEvent event = event(Action.BLOCK_BREAK, "minecraft:stone", 0, null);
        Component line = lines.line(event);
        assertEquals("3h12m Alex broke stone (10/64/-3/world)", plain(line));
        assertTrue(hovers(line).contains("2026-11-16T08:48:00"), hovers(line).toString());
    }

    @Test
    public void aLocalLocationTeleportsWhenClicked() {
        Component location = lines.location(event(Action.BLOCK_BREAK, "minecraft:stone", 0, null));
        assertEquals("(10/64/-3/world)", plain(location));
        assertEquals(List.of("/hawkeye tp world 10 64 -3"), commands(location));
    }

    @Test
    public void aRemoteLocationNamesTheServerAndIsNotClickable() {
        StoredEvent remote = new StoredEvent(EventIds.compose(NOW.toEpochMilli(), 2, 0), "creative", "world",
                BlockPositions.pack(1, 2, 3), ALEX, Action.BLOCK_PLACE, "minecraft:dirt", "minecraft:air", 0, false,
                null);
        Component location = lines.location(remote);
        assertEquals("(1/2/3/world@creative)", plain(location));
        assertTrue(commands(location).isEmpty());
    }

    @Test
    public void aRolledBackLineIsStruckThrough() {
        StoredEvent rolledBack = new StoredEvent(EventIds.compose(NOW.toEpochMilli(), 1, 0), "survival", "world",
                BlockPositions.pack(0, 64, 0), ALEX, Action.BLOCK_BREAK, "minecraft:stone", "minecraft:air", 0, true,
                null);
        assertTrue(decorated(lines.line(rolledBack), TextDecoration.STRIKETHROUGH));
        assertTrue(!decorated(lines.line(event(Action.BLOCK_BREAK, "minecraft:stone", 0, null)),
                TextDecoration.STRIKETHROUGH));
    }

    private static StoredEvent event(Action action, String subject, int amount, @Nullable Payload payload) {
        long millis = NOW.minus(Duration.ofMinutes(192)).toEpochMilli();
        return new StoredEvent(EventIds.compose(millis, 1, 0), "survival", "world", BlockPositions.pack(10, 64, -3),
                ALEX, action, subject, null, amount, false, payload == null ? null : PayloadCodec.encode(payload));
    }

    private static String json(String text) {
        return GsonComponentSerializer.gson().serialize(Component.text(text));
    }

    static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    static List<String> commands(Component component) {
        List<String> commands = new ArrayList<>();
        walk(component, part -> {
            ClickEvent<?> click = part.clickEvent();
            if (click != null && click.payload() instanceof ClickEvent.Payload.Text text) {
                commands.add(text.value());
            }
        });
        return commands;
    }

    private static List<String> hovers(Component component) {
        List<String> hovers = new ArrayList<>();
        walk(component, part -> {
            HoverEvent<?> hover = part.hoverEvent();
            if (hover != null && hover.value() instanceof Component text) {
                hovers.add(plain(text));
            }
        });
        return hovers;
    }

    private static boolean decorated(Component component, TextDecoration decoration) {
        List<Boolean> found = new ArrayList<>();
        walk(component, part -> {
            if (part.decoration(decoration) == TextDecoration.State.TRUE) {
                found.add(true);
            }
        });
        return !found.isEmpty();
    }

    private static void walk(Component component, Consumer<Component> visitor) {
        visitor.accept(component);
        component.children().forEach(child -> walk(child, visitor));
    }
}
