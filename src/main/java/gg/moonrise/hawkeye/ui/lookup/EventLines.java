package gg.moonrise.hawkeye.ui.lookup;

import gg.moonrise.engine.message.Message;
import gg.moonrise.hawkeye.config.message.ActionMessages;
import gg.moonrise.hawkeye.config.message.LookupMessages;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.entity.payload.Payload;
import gg.moonrise.hawkeye.entity.payload.SignSides;
import gg.moonrise.hawkeye.util.text.Durations;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
     * What happened ({@code <action>}): the action's template with its target, amount and text.
     *
     * @param event the event
     * @return the action text
     */
    public @NotNull Component action(@NotNull StoredEvent event) {
        return Message.of(template(event)).asComponent(
                Placeholder.component("target", target(event.subject())),
                literal("amount", Integer.toString(event.amount())),
                Placeholder.component("text", text(event).orElse(Component.empty())));
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
     * {@code <text>}: the chat message or command line, or the sign lines after an edit (only the side
     * that changed, when only one did).
     *
     * @param event the event
     * @return the text, or empty when the event carries none
     */
    public static @NotNull Optional<Component> text(@NotNull StoredEvent event) {
        Optional<Payload> payload;
        try {
            payload = event.payload();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        return payload.flatMap(value -> switch (value) {
            case Payload.Text text -> Optional.of(Component.text(text.text()));
            case Payload.Sign sign -> signText(sign);
            case Payload.Item item -> Optional.empty();
            case Payload.Inventory inventory -> Optional.empty();
            case Payload.Entity entity -> Optional.empty();
            case Payload.EntityRef ref -> Optional.empty();
        });
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
