package gg.moonrise.hawkeye.core.service.query.syntax;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.entity.action.Action;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

public class ActionAliasesTest {

    /** Each of CoreProtect's synonyms, by the canonical alias it means. */
    private static final Map<String, List<String>> SYNONYMS = Map.ofEntries(
            Map.entry("block", List.of("blocks", "block-change", "changes")),
            Map.entry("-block", List.of("broke", "break", "remove", "destroy", "block-break", "block-remove",
                    "-blocks", "block-")),
            Map.entry("+block", List.of("placed", "place", "block-place", "+blocks", "block+")),
            Map.entry("click", List.of("clicks", "interact", "interaction", "player-interact",
                    "player-interaction", "player-click")),
            Map.entry("kill", List.of("death", "deaths", "entity-death", "entity-deaths", "kills", "entity-kill",
                    "entity-kills")),
            Map.entry("spawn", List.of("spawns", "entity-spawn", "entity-spawns")),
            Map.entry("container", List.of("containers", "container-change", "chest", "transaction",
                    "transactions")),
            Map.entry("-container", List.of("container-", "remove-container")),
            Map.entry("+container", List.of("container+", "container-add", "add-container")),
            Map.entry("chat", List.of("chats", "message", "messages")),
            Map.entry("command", List.of("commands")),
            Map.entry("+session", List.of("login", "logins", "+sessions", "session+", "+connection",
                    "connection+")),
            Map.entry("-session", List.of("logout", "logouts", "-sessions", "session-", "-connection",
                    "connection-")),
            Map.entry("session", List.of("sessions", "connection", "connections")),
            Map.entry("sign", List.of("signs")),
            Map.entry("item", List.of("items")),
            Map.entry("-item", List.of("-items", "item-", "items-", "drop", "drops")),
            Map.entry("+item", List.of("+items", "item+", "items+", "pickup", "pickups")));

    @Test
    public void everyCoreProtectSynonymMeansItsCanonicalAlias() {
        SYNONYMS.forEach((alias, spellings) -> {
            Set<Action> expected = ActionAliases.of(alias).orElseThrow();
            for (String spelling : spellings) {
                assertEquals(expected, ActionAliases.of(spelling).orElseThrow(), spelling);
                assertEquals(expected, ActionAliases.of(spelling.toUpperCase(Locale.ROOT)).orElseThrow(), spelling);
            }
        });
    }

    @Test
    public void oneLeadingHashIsIgnored() {
        assertEquals(ActionAliases.of("block"), ActionAliases.of("#block"));
        assertEquals(ActionAliases.of("-block"), ActionAliases.of("#Broke"));
        assertTrue(ActionAliases.of("##block").isEmpty());
        assertTrue(ActionAliases.of("#").isEmpty());
    }

    @Test
    public void depositAndWithdrawStayUnknown() {
        assertTrue(ActionAliases.of("deposit").isEmpty());
        assertTrue(ActionAliases.of("withdraw").isEmpty());
    }

    @Test
    public void suggestionsListOnlyTheCanonicalNames() {
        List<String> names = ActionAliases.names();
        assertEquals(28, names.size());
        assertTrue(names.contains("-block"));
        SYNONYMS.values().forEach(spellings -> spellings.forEach(spelling ->
                assertFalse(names.contains(spelling), spelling)));
    }
}
