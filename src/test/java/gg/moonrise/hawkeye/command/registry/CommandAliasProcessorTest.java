package gg.moonrise.hawkeye.command.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;

import gg.moonrise.hawkeye.config.file.CommandsConfig;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

public class CommandAliasProcessorTest {

    private static final Set<String> SUBCOMMANDS = Set.of("lookup", "near", "inspect", "help");

    @Test
    public void addsTheRootAndSubcommandAliases() {
        CommandAliasProcessor processor = CommandAliasProcessor.of(new CommandsConfig(), SUBCOMMANDS);
        assertEquals("hawkeye|he", processor.processString("hawkeye"));
        assertEquals("hawkeye|he lookup|l [parameters]", processor.processString("hawkeye lookup [parameters]"));
        assertEquals("hawkeye|he inspect|i", processor.processString("hawkeye inspect"));
        assertEquals("hawkeye|he near [radius]", processor.processString("hawkeye near [radius]"));
        assertEquals("hawkeye|he import coreprotect [source]",
                processor.processString("hawkeye import coreprotect [source]"));
    }

    @Test
    public void leavesOtherAnnotationStringsAlone() {
        CommandAliasProcessor processor = CommandAliasProcessor.of(new CommandsConfig(), SUBCOMMANDS);
        assertEquals("hawkeye.lookup", processor.processString("hawkeye.lookup"));
        assertEquals("Shows the Hawkeye version", processor.processString("Shows the Hawkeye version"));
        assertEquals("parameters", processor.processString("parameters"));
        assertEquals("", processor.processString(""));
    }

    @Test
    public void usesTheConfiguredAliases() throws ReflectiveOperationException {
        Map<String, List<String>> aliases = new LinkedHashMap<>();
        aliases.put("lookup", List.of("l", "search"));
        aliases.put("help", List.of());
        CommandAliasProcessor processor = CommandAliasProcessor.of(config(List.of("he", "co"), aliases), SUBCOMMANDS);
        assertEquals("hawkeye|he|co lookup|l|search [parameters]",
                processor.processString("hawkeye lookup [parameters]"));
        assertEquals("hawkeye|he|co help", processor.processString("hawkeye help"));
        assertEquals("hawkeye|he|co inspect", processor.processString("hawkeye inspect"));
    }

    @Test
    public void withoutAliasesTheNamesStayAsTheyAre() throws ReflectiveOperationException {
        CommandAliasProcessor processor = CommandAliasProcessor.of(config(List.of(), Map.of()), SUBCOMMANDS);
        assertEquals("hawkeye lookup [parameters]", processor.processString("hawkeye lookup [parameters]"));
    }

    @Test
    public void skipsUnknownSubcommandsAndAliasesThatShadowOne() throws ReflectiveOperationException {
        Map<String, List<String>> aliases = new LinkedHashMap<>();
        aliases.put("lookup", List.of("near", "l"));
        aliases.put("inspect", List.of("help"));
        aliases.put("missing", List.of("m"));
        CommandAliasProcessor processor = CommandAliasProcessor.of(config(List.of(), aliases), SUBCOMMANDS);
        assertEquals("hawkeye lookup|l", processor.processString("hawkeye lookup"));
        assertEquals("hawkeye inspect", processor.processString("hawkeye inspect"));
        assertEquals("hawkeye missing", processor.processString("hawkeye missing"));
    }

    private static CommandsConfig config(List<String> root, Map<String, List<String>> subcommands)
            throws ReflectiveOperationException {
        CommandsConfig config = new CommandsConfig();
        set(config, "rootAliases", root);
        set(config, "subcommandAliases", subcommands);
        return config;
    }

    private static void set(CommandsConfig config, String name, Object value) throws ReflectiveOperationException {
        Field field = CommandsConfig.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(config, value);
    }
}
