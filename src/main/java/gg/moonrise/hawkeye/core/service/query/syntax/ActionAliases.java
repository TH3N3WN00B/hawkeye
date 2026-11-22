package gg.moonrise.hawkeye.core.service.query.syntax;

import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.action.ActionFamily;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jetbrains.annotations.NotNull;

/**
 * The {@code a:} vocabulary, kept compatible with CoreProtect's so staff can switch without relearning:
 * {@code +} means added or placed, {@code -} removed or broken. CoreProtect's synonyms ({@code broke},
 * {@code placed}, {@code login}, {@code drops}, ...) and a leading {@code #} ({@code a:#block}) are
 * accepted too, but only the canonical names are suggested.
 */
public final class ActionAliases {

    private static final Map<String, Set<Action>> ALIASES = aliases();
    private static final Map<String, String> SYNONYMS = synonyms();

    private ActionAliases() {
    }

    /**
     * The actions an alias stands for.
     *
     * @param alias e.g. {@code -block}, {@code broke} or {@code #block}, any case
     * @return the actions, or empty for an unknown alias
     */
    public static @NotNull Optional<Set<Action>> of(@NotNull String alias) {
        String name = alias.toLowerCase(Locale.ROOT);
        if (name.startsWith("#")) {
            name = name.substring(1);
        }
        Set<Action> actions = ALIASES.get(SYNONYMS.getOrDefault(name, name));
        return Optional.ofNullable(actions);
    }

    /**
     * Every canonical alias, for suggestions (CoreProtect's synonyms are accepted but not listed).
     *
     * @return the aliases in a stable order
     */
    public static @NotNull List<String> names() {
        return List.copyOf(ALIASES.keySet());
    }

    private static @NotNull Map<String, Set<Action>> aliases() {
        Map<String, Set<Action>> aliases = new LinkedHashMap<>();
        aliases.put("block", Set.of(Action.BLOCK_BREAK, Action.BLOCK_PLACE, Action.BLOCK_CHANGE));
        aliases.put("+block", Set.of(Action.BLOCK_PLACE));
        aliases.put("-block", Set.of(Action.BLOCK_BREAK));
        aliases.put("change", Set.of(Action.BLOCK_CHANGE));
        aliases.put("sign", Set.of(Action.SIGN_EDIT));
        aliases.put("click", Set.of(Action.INTERACT));
        aliases.put("grow", Set.of(Action.BLOCK_GROW));
        aliases.put("container", Set.of(Action.CONTAINER_DEPOSIT, Action.CONTAINER_WITHDRAW));
        aliases.put("+container", Set.of(Action.CONTAINER_DEPOSIT));
        aliases.put("-container", Set.of(Action.CONTAINER_WITHDRAW));
        aliases.put("item", Set.of(Action.ITEM_DROP, Action.ITEM_PICKUP, Action.ITEM_CRAFT, Action.ITEM_THROW,
                Action.ITEM_BREAK, Action.ITEM_TRADE));
        aliases.put("+item", Set.of(Action.ITEM_PICKUP));
        aliases.put("-item", Set.of(Action.ITEM_DROP));
        aliases.put("craft", Set.of(Action.ITEM_CRAFT));
        aliases.put("throw", Set.of(Action.ITEM_THROW));
        aliases.put("trade", Set.of(Action.ITEM_TRADE));
        aliases.put("kill", Set.of(Action.ENTITY_KILL));
        aliases.put("entity", Set.of(Action.of(ActionFamily.ENTITY)));
        aliases.put("+entity", Set.of(Action.ENTITY_SPAWN));
        aliases.put("-entity", Set.of(Action.ENTITY_REMOVE, Action.ENTITY_KILL));
        aliases.put("spawn", Set.of(Action.ENTITY_SPAWN));
        aliases.put("chat", Set.of(Action.CHAT));
        aliases.put("command", Set.of(Action.COMMAND));
        aliases.put("book", Set.of(Action.BOOK_EDIT));
        aliases.put("gamemode", Set.of(Action.GAMEMODE_CHANGE));
        aliases.put("session", Set.of(Action.JOIN, Action.QUIT));
        aliases.put("+session", Set.of(Action.JOIN));
        aliases.put("-session", Set.of(Action.QUIT));
        return aliases;
    }

    /** CoreProtect's other spellings, each to the canonical alias it means. */
    private static @NotNull Map<String, String> synonyms() {
        Map<String, String> synonyms = new LinkedHashMap<>();
        synonym(synonyms, "block", "blocks", "block-change", "changes");
        synonym(synonyms, "-block", "broke", "break", "remove", "destroy", "block-break", "block-remove", "-blocks",
                "block-");
        synonym(synonyms, "+block", "placed", "place", "block-place", "+blocks", "block+");
        synonym(synonyms, "click", "clicks", "interact", "interaction", "player-interact", "player-interaction",
                "player-click");
        synonym(synonyms, "kill", "death", "deaths", "entity-death", "entity-deaths", "kills", "entity-kill",
                "entity-kills");
        synonym(synonyms, "spawn", "spawns", "entity-spawn", "entity-spawns");
        synonym(synonyms, "container", "containers", "container-change", "chest", "transaction", "transactions");
        synonym(synonyms, "-container", "container-", "remove-container");
        synonym(synonyms, "+container", "container+", "container-add", "add-container");
        synonym(synonyms, "chat", "chats", "message", "messages");
        synonym(synonyms, "command", "commands");
        synonym(synonyms, "+session", "login", "logins", "+sessions", "session+", "+connection", "connection+");
        synonym(synonyms, "-session", "logout", "logouts", "-sessions", "session-", "-connection", "connection-");
        synonym(synonyms, "session", "sessions", "connection", "connections");
        synonym(synonyms, "sign", "signs");
        synonym(synonyms, "item", "items");
        synonym(synonyms, "-item", "-items", "item-", "items-", "drop", "drops");
        synonym(synonyms, "+item", "+items", "item+", "items+", "pickup", "pickups");
        return synonyms;
    }

    private static void synonym(@NotNull Map<String, String> synonyms, @NotNull String alias,
            @NotNull String... spellings) {
        for (String spelling : spellings) {
            synonyms.put(spelling, alias);
        }
    }
}
