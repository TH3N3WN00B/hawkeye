package gg.moonrise.hawkeye.config.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class CommandsConfigTest {

    @Test
    public void defaultsKeepTheBuiltInAliases() {
        CommandsConfig config = new CommandsConfig();
        assertEquals(List.of("he"), config.rootAliases());
        assertEquals(Map.of("inspect", List.of("i"), "lookup", List.of("l"), "rollback", List.of("rb"),
                "restore", List.of("rs")), config.subcommandAliases());
        assertEquals(List.of(), config.problems());
    }

    @Test
    public void blankAndMissingEntriesAreDropped() throws ReflectiveOperationException {
        CommandsConfig config = new CommandsConfig();
        set(config, "rootAliases", Arrays.asList(" co ", "", null));
        Map<String, List<String>> aliases = new LinkedHashMap<>();
        aliases.put("lookup", null);
        aliases.put(" ", List.of("x"));
        set(config, "subcommandAliases", aliases);
        assertEquals(List.of("co"), config.rootAliases());
        assertEquals(Map.of("lookup", List.of()), config.subcommandAliases());
        assertEquals(List.of(), config.problems());

        set(config, "rootAliases", null);
        set(config, "subcommandAliases", null);
        assertEquals(List.of(), config.rootAliases());
        assertEquals(Map.of(), config.subcommandAliases());
    }

    @Test
    public void refusesNamesThatAreNotOneCommandWord() throws ReflectiveOperationException {
        CommandsConfig config = new CommandsConfig();
        set(config, "rootAliases", List.of("co", "Core Protect", "a|b"));
        set(config, "subcommandAliases", Map.of("look up", List.of("l")));
        List<String> problems = config.problems();
        assertEquals(3, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("'Core Protect'"), problems.toString());
        assertTrue(problems.get(1).contains("'a|b'"), problems.toString());
        assertTrue(problems.get(2).contains("'look up'"), problems.toString());
    }

    @Test
    public void refusesAnAliasUsedTwice() throws ReflectiveOperationException {
        CommandsConfig config = new CommandsConfig();
        set(config, "rootAliases", List.of("co", "co", "hawkeye"));
        Map<String, List<String>> aliases = new LinkedHashMap<>();
        aliases.put("lookup", List.of("l", "lookup"));
        aliases.put("inspect", List.of("l"));
        set(config, "subcommandAliases", aliases);
        List<String> problems = config.problems();
        assertEquals(4, problems.size(), problems.toString());
        assertTrue(problems.get(0).startsWith("root-aliases: 'co'"), problems.toString());
        assertTrue(problems.get(1).startsWith("root-aliases: 'hawkeye'"), problems.toString());
        assertTrue(problems.get(2).startsWith("subcommand-aliases.lookup: 'lookup'"), problems.toString());
        assertTrue(problems.get(3).startsWith("subcommand-aliases.inspect: 'l'"), problems.toString());
    }

    private static void set(CommandsConfig config, String name, Object value) throws ReflectiveOperationException {
        Field field = CommandsConfig.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(config, value);
    }
}
