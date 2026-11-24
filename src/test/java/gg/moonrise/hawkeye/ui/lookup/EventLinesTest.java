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
import gg.moonrise.hawkeye.util.serialize.ItemSummaryTest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
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

    private final EventLines lines = new EventLines(new LookupMessages(), new ActionMessages(), "survival", NOW,
            ZoneOffset.UTC);

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
