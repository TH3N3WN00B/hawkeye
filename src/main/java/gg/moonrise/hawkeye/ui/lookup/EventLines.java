package gg.moonrise.hawkeye.ui.lookup;

import gg.moonrise.engine.message.Message;
import gg.moonrise.hawkeye.config.message.ActionMessages;
import gg.moonrise.hawkeye.config.message.LookupMessages;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.entity.payload.Payload;
import gg.moonrise.hawkeye.entity.payload.SignSides;
import gg.moonrise.hawkeye.util.serialize.ItemSummary;
import gg.moonrise.hawkeye.util.text.Durations;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jetbrains.annotations.NotNull;

/**
 * Renders one result line per event from {@code messages.yml}: a pure function of the messages, the
 * name of this server (events of other servers get a location that is not clickable), the current
 * time and the server's time zone.
 *
 * @param lookup  the {@code lookup} messages
 * @param actions the {@code actions} messages
 * @param server  this server's name ({@code server.name})
 * @param now     the time ages are measured from
 * @param zone    the zone of {@code <date>}
 */
public record EventLines(@NotNull LookupMessages lookup, @NotNull ActionMessages actions, @NotNull String server,
        @NotNull Instant now, @NotNull ZoneId zone) {

    private static final String MINECRAFT = "minecraft:";

    /**
     * One result line ({@code lookup.line}).
     *
     * @param event the event
     * @return the line
     */
    public @NotNull Component line(@NotNull StoredEvent event) {
        Instant at = Instant.ofEpochMilli(event.epochMillis());
        return Message.of(lookup.getLine()).asComponent(
                literal("time", Durations.format(Duration.between(at, now))),
                literal("date", date(at)),
                literal("actor", event.actor().name()),
                Placeholder.component("action", action(event)),
                Placeholder.component("location", location(event)),
                literal("server", event.server()),
                literal("world", event.world()),
                literal("x", Integer.toString(event.x())),
                literal("y", Integer.toString(event.y())),
                literal("z", Integer.toString(event.z())),
                Placeholder.parsed("rolled-back", event.rolledBack() ? lookup.getRolledBackMarker() : ""));
    }

    /**
     * What happened ({@code <action>}): the action's template with its target, amount, text and
     * snapshot contents.
     *
     * @param event the event
     * @return the action text
     */
    public @NotNull Component action(@NotNull StoredEvent event) {
        Optional<Payload> payload = payload(event);
        return Message.of(template(event)).asComponent(
                Placeholder.component("target", target(event.subject())),
                literal("amount", Integer.toString(event.amount())),
                Placeholder.component("text", payload.flatMap(EventLines::payloadText).orElse(Component.empty())),
                Placeholder.component("contents", payload.orElse(null) instanceof Payload.Snapshot snapshot
                        ? contents(snapshot, event.amount()) : Component.empty()));
    }

    /**
     * {@code <contents>} of an inventory snapshot: {@code lookup.snapshot-contents}, with a hover listing
     * one {@code lookup.snapshot-item} per slot in slot order ({@code lookup.snapshot-empty} without
     * items). A slot whose bytes are not readable item NBT is counted but not listed.
     *
     * @param snapshot the snapshot
     * @param amount   the number of items in it (the event's amount)
     * @return the contents text
     */
    public @NotNull Component contents(@NotNull Payload.Snapshot snapshot, int amount) {
        Component contents = Message.of(lookup.getSnapshotContents()).asComponent(
                literal("stacks", Integer.toString(snapshot.slots().size())),
                literal("amount", Integer.toString(amount)));
        List<Component> items = new ArrayList<>(snapshot.slots().size());
        snapshot.slots().stream().sorted(Comparator.comparingInt(Payload.Slot::index)).forEach(slot ->
                ItemSummary.of(slot.item()).ifPresent(item -> items.add(Message.of(lookup.getSnapshotItem())
                        .asComponent(
                                literal("amount", Integer.toString(item.count())),
                                literal("target", shortName(item.id())),
                                literal("slot", Integer.toString(slot.index()))))));
        if (snapshot.slots().isEmpty()) {
            return contents.hoverEvent(HoverEvent.showText(Message.of(lookup.getSnapshotEmpty()).asComponent()));
        }
        return items.isEmpty() ? contents
                : contents.hoverEvent(HoverEvent.showText(Component.join(JoinConfiguration.newlines(), items)));
    }

    /**
     * Where it happened ({@code <location>}): {@code lookup.location} (clickable) for this server,
     * {@code lookup.remote-location} for another.
     *
     * @param event the event
     * @return the location text
     */
    public @NotNull Component location(@NotNull StoredEvent event) {
        String template = event.server().equals(server) ? lookup.getLocation() : lookup.getRemoteLocation();
        return Message.of(template).asComponent(
                literal("world", event.world()),
                literal("x", Integer.toString(event.x())),
                literal("y", Integer.toString(event.y())),
                literal("z", Integer.toString(event.z())),
                literal("server", event.server()));
    }

    /**
     * A namespaced value as shown in a line: without the {@code minecraft:} namespace and without
     * block-state properties ({@code minecraft:oak_stairs[facing=east]} reads {@code oak_stairs}).
     *
     * @param value a block state, material or entity type
     * @return the short name
     */
    public static @NotNull String shortName(@NotNull String value) {
        int properties = value.indexOf('[');
        String name = properties < 0 ? value : value.substring(0, properties);
        return name.startsWith(MINECRAFT) ? name.substring(MINECRAFT.length()) : name;
    }

    /**
     * {@code <target>}: the short name, with the full value as its hover.
     *
     * @param value a block state, material or entity type, or empty
     * @return the target, or an empty component for an empty value
     */
    public static @NotNull Component target(@NotNull String value) {
        if (value.isEmpty()) {
            return Component.empty();
        }
        return Component.text(shortName(value)).hoverEvent(HoverEvent.showText(Component.text(value)));
    }

    /**
     * {@code <text>}: the chat message or command line, the sign lines after an edit (only the side
     * that changed, when only one did), an inventory snapshot's detail (where a teleport went, the
     * death message), or the inventory of a virtual container transaction.
     *
     * @param event the event
     * @return the text, or empty when the event carries none
     */
    public static @NotNull Optional<Component> text(@NotNull StoredEvent event) {
        return payload(event).flatMap(EventLines::payloadText);
    }

    private static @NotNull Optional<Component> payloadText(@NotNull Payload payload) {
        return switch (payload) {
            case Payload.Text text -> Optional.of(Component.text(text.text()));
            case Payload.Sign sign -> signText(sign);
            case Payload.Item item -> Optional.empty();
            case Payload.Inventory inventory -> Optional.empty();
            case Payload.Entity entity -> Optional.empty();
            case Payload.EntityRef ref -> Optional.empty();
            case Payload.Snapshot snapshot -> snapshot.detail().isBlank() ? Optional.empty()
                    : Optional.of(Component.text(snapshot.detail()));
            case Payload.VirtualItem virtual -> Optional.of(Component.text(virtual.container()));
        };
    }

    /** The event's payload, or empty when it has none or the stored bytes cannot be decoded. */
    private static @NotNull Optional<Payload> payload(@NotNull StoredEvent event) {
        try {
            return event.payload();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private @NotNull String template(@NotNull StoredEvent event) {
        return switch (event.action()) {
            case BLOCK_BREAK -> actions.getBlockBreak();
            case BLOCK_PLACE -> actions.getBlockPlace();
            case BLOCK_CHANGE -> actions.getBlockChange();
            case SIGN_EDIT -> actions.getSignEdit();
            case INTERACT -> actions.getInteract();
            case BLOCK_GROW -> actions.getBlockGrow();
            case CONTAINER_DEPOSIT -> actions.getContainerDeposit();
            case CONTAINER_WITHDRAW -> actions.getContainerWithdraw();
            case VIRTUAL_DEPOSIT -> actions.getVirtualDeposit();
            case VIRTUAL_WITHDRAW -> actions.getVirtualWithdraw();
            case ITEM_DROP -> actions.getItemDrop();
            case ITEM_PICKUP -> actions.getItemPickup();
            case ITEM_CRAFT -> actions.getItemCraft();
            case ITEM_THROW -> actions.getItemThrow();
            case ITEM_BREAK -> actions.getItemBreak();
            case ITEM_TRADE -> actions.getItemTrade();
            case ENTITY_KILL -> actions.getEntityKill();
            case ENTITY_SPAWN -> actions.getEntitySpawn();
            case ENTITY_REMOVE -> actions.getEntityRemove();
            case ENTITY_SHEAR -> actions.getEntityShear();
            case ENTITY_NAME -> actions.getEntityName();
            case ENTITY_LEASH -> actions.getEntityLeash();
            case ENTITY_UNLEASH -> actions.getEntityUnleash();
            case ENTITY_TAME -> actions.getEntityTame();
            case ENTITY_DYE -> actions.getEntityDye();
            case CHAT -> actions.getChat();
            case COMMAND -> actions.getCommand();
            case BOOK_EDIT -> actions.getBookEdit();
            case GAMEMODE_CHANGE -> actions.getGamemodeChange();
            case JOIN -> actions.getJoin();
            case QUIT -> actions.getQuit();
            case SNAPSHOT_JOIN -> actions.getSnapshotJoin();
            case SNAPSHOT_QUIT -> actions.getSnapshotQuit();
            case SNAPSHOT_TELEPORT -> actions.getSnapshotTeleport();
            case SNAPSHOT_DEATH -> actions.getSnapshotDeath();
        };
    }

    private @NotNull String date(@NotNull Instant at) {
        return LocalDateTime.ofInstant(at, zone).truncatedTo(ChronoUnit.SECONDS)
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    private static @NotNull Optional<Component> signText(@NotNull Payload.Sign sign) {
        SignSides before = sign.before();
        SignSides after = sign.after();
        boolean front = !before.front().equals(after.front());
        boolean back = !before.back().equals(after.back());
        List<String> lines = new ArrayList<>();
        if (front || !back) {
            lines.addAll(after.front());
        }
        if (back || !front) {
            lines.addAll(after.back());
        }
        List<Component> shown = new ArrayList<>();
        for (String line : lines) {
            Component component = signLine(line);
            if (!PlainTextComponentSerializer.plainText().serialize(component).isBlank()) {
                shown.add(component);
            }
        }
        return shown.isEmpty() ? Optional.empty() : Optional.of(Component.join(JoinConfiguration.spaces(), shown));
    }

    private static @NotNull Component signLine(@NotNull String json) {
        try {
            return GsonComponentSerializer.gson().deserialize(json);
        } catch (RuntimeException e) {
            return Component.text(json);
        }
    }

    /**
     * A plain value that also works inside tag arguments ({@code <click:run_command:'/tp <x>'>}): parsed
     * placeholders are substituted before the template is read, and escaping keeps the value literal.
     */
    private static @NotNull TagResolver.Single literal(@NotNull String key, @NotNull String value) {
        return Placeholder.parsed(key, MiniMessage.miniMessage().escapeTags(value));
    }
}
