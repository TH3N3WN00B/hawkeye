package gg.moonrise.hawkeye.command.registry;

import gg.moonrise.hawkeye.config.file.CommandsConfig;
import gg.moonrise.hawkeye.config.permission.Permissions;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.incendo.cloud.annotations.string.StringProcessor;
import org.jetbrains.annotations.NotNull;

/**
 * Adds the {@code commands.yml} aliases to the command strings of the annotation parser. Cloud runs
 * every annotation string through it (descriptions and permissions too), so it rewrites only a string
 * whose first word is {@link Permissions#ROOT}: the root becomes {@code hawkeye|alias|...} and a
 * literal subcommand with aliases becomes {@code lookup|l|...}. Everything else is returned unchanged.
 */
@Slf4j
public final class CommandAliasProcessor implements StringProcessor {

    private final @NotNull String root;
    private final @NotNull Map<String, String> subcommands;

    private CommandAliasProcessor(@NotNull String root, @NotNull Map<String, String> subcommands) {
        this.root = root;
        this.subcommands = Map.copyOf(subcommands);
    }

    /**
     * Builds the processor for the configured aliases. An alias for a subcommand that does not exist,
     * or one that is the name of another subcommand, is skipped with a warning.
     *
     * @param config      the aliases
     * @param subcommands the literal subcommand names the command beans declare
     * @return the processor
     */
    public static @NotNull CommandAliasProcessor of(@NotNull CommandsConfig config,
                                                    @NotNull Set<String> subcommands) {
        Map<String, String> literals = new HashMap<>();
        config.subcommandAliases().forEach((name, aliases) -> {
            if (!subcommands.contains(name)) {
                log.warn("commands.yml: there is no subcommand '{}'; its aliases are ignored", name);
                return;
            }
            List<String> usable = new ArrayList<>();
            for (String alias : aliases) {
                if (subcommands.contains(alias)) {
                    log.warn("commands.yml: '{}' is already a subcommand; it is not an alias of '{}'", alias, name);
                } else {
                    usable.add(alias);
                }
            }
            if (!usable.isEmpty()) {
                literals.put(name, join(name, usable));
            }
        });
        return new CommandAliasProcessor(join(Permissions.ROOT, config.rootAliases()), literals);
    }

    @Override
    public @NotNull String processString(@NotNull String input) {
        String[] words = input.split(" ", 3);
        if (!words[0].equals(Permissions.ROOT)) {
            return input;
        }
        StringBuilder command = new StringBuilder(root);
        if (words.length > 1) {
            command.append(' ').append(subcommands.getOrDefault(words[1], words[1]));
        }
        if (words.length > 2) {
            command.append(' ').append(words[2]);
        }
        return command.toString();
    }

    private static @NotNull String join(@NotNull String name, @NotNull Collection<String> aliases) {
        if (aliases.isEmpty()) {
            return name;
        }
        return name + "|" + String.join("|", aliases);
    }
}
