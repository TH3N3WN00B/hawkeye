package gg.moonrise.hawkeye.config.file;

import de.exlll.configlib.Comment;
import de.exlll.configlib.Configuration;
import gg.moonrise.hawkeye.config.permission.Permissions;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The {@code commands.yml} model: extra names for {@code /hawkeye} and its subcommands. Aliases are
 * registered once at startup, so a change needs a restart. Keys are a contract with operators.
 */
@EqualsAndHashCode
@ToString
@Configuration
public final class CommandsConfig {

    /** What an alias may look like: a single lowercase command word. */
    public static final Pattern ALIAS = Pattern.compile("[a-z0-9_-]{1,32}");

    @Comment({"Extra names for /hawkeye. Add co to keep /co working for staff used to CoreProtect; a name another",
            "plugin also registers (CoreProtect's /co) is only reliable once that plugin is removed."})
    private List<String> rootAliases = List.of("he");

    @Comment({"Extra names for subcommands, by subcommand: lookup: [l, search] makes /hawkeye search a lookup.",
            "An alias may not be the name of another subcommand."})
    private Map<String, List<String>> subcommandAliases = defaultSubcommandAliases();

    /**
     * The extra names of the root command.
     *
     * @return the aliases in file order, without blank entries
     */
    public @NotNull List<String> rootAliases() {
        return clean(rootAliases);
    }

    /**
     * The extra names of each subcommand.
     *
     * @return subcommand name to its aliases, in file order, without blank entries
     */
    public @NotNull Map<String, List<String>> subcommandAliases() {
        Map<String, List<String>> aliases = new LinkedHashMap<>();
        if (subcommandAliases != null) {
            subcommandAliases.forEach((name, names) -> {
                if (name != null && !name.isBlank()) {
                    aliases.put(name.trim(), clean(names));
                }
            });
        }
        return aliases;
    }

    /**
     * Validation problems.
     *
     * @return one message per problem
     */
    public @NotNull List<String> problems() {
        List<String> problems = new ArrayList<>();
        Set<String> rootNames = new HashSet<>(Set.of(Permissions.ROOT));
        for (String alias : rootAliases()) {
            checkName(problems, "root-aliases", alias);
            if (!rootNames.add(alias)) {
                problems.add("root-aliases: '" + alias + "' is listed twice or is the command's own name");
            }
        }
        Set<String> subNames = new HashSet<>();
        subcommandAliases().forEach((name, aliases) -> {
            checkName(problems, "subcommand-aliases", name);
            for (String alias : aliases) {
                checkName(problems, "subcommand-aliases." + name, alias);
                if (alias.equals(name) || !subNames.add(alias)) {
                    problems.add("subcommand-aliases." + name + ": '" + alias + "' is listed twice or is the subcommand's own name");
                }
            }
        });
        return problems;
    }

    private static void checkName(@NotNull List<String> problems, @NotNull String path, @NotNull String name) {
        if (!ALIAS.matcher(name).matches()) {
            problems.add(path + ": '" + name + "' must be up to 32 of a-z, 0-9, _ and -");
        }
    }

    private static @NotNull List<String> clean(@Nullable List<String> names) {
        if (names == null) {
            return List.of();
        }
        return names.stream().filter(Objects::nonNull).map(String::trim).filter(name -> !name.isEmpty()).toList();
    }

    private static @NotNull Map<String, List<String>> defaultSubcommandAliases() {
        Map<String, List<String>> aliases = new LinkedHashMap<>();
        aliases.put("inspect", List.of("i"));
        aliases.put("lookup", List.of("l"));
        aliases.put("rollback", List.of("rb"));
        aliases.put("restore", List.of("rs"));
        return aliases;
    }
}
