package gg.moonrise.hawkeye.core.service.query;

import gg.moonrise.hawkeye.entity.action.Action;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jetbrains.annotations.NotNull;

/**
 * The {@code a:} vocabulary, kept compatible with CoreProtect's so staff can switch without relearning:
 * {@code +} means added or placed, {@code -} removed or broken.
 */
public final class ActionAliases {

    private static final Map<String, Set<Action>> ALIASES = aliases();

    private ActionAliases() {
    }

    /**
     * The actions an alias stands for.
     *
     * @param alias e.g. {@code -block}, any case
     * @return the actions, or empty for an unknown alias
     */
    public static @NotNull Optional<Set<Action>> of(@NotNull String alias) {
        return Optional.ofNullable(ALIASES.get(alias.toLowerCase(Locale.ROOT)));
    }

    /**
     * Every alias, for suggestions.
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
        aliases.put("container", Set.of(Action.CONTAINER_DEPOSIT, Action.CONTAINER_WITHDRAW));
        aliases.put("+container", Set.of(Action.CONTAINER_DEPOSIT));
        aliases.put("-container", Set.of(Action.CONTAINER_WITHDRAW));
        aliases.put("item", Set.of(Action.ITEM_DROP, Action.ITEM_PICKUP));
        aliases.put("+item", Set.of(Action.ITEM_PICKUP));
        aliases.put("-item", Set.of(Action.ITEM_DROP));
        aliases.put("kill", Set.of(Action.ENTITY_KILL));
        aliases.put("chat", Set.of(Action.CHAT));
        aliases.put("command", Set.of(Action.COMMAND));
        aliases.put("session", Set.of(Action.JOIN, Action.QUIT));
        aliases.put("+session", Set.of(Action.JOIN));
        aliases.put("-session", Set.of(Action.QUIT));
        return aliases;
    }
}
