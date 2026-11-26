package gg.moonrise.hawkeye.command.registry;

import gg.moonrise.engine.command.CloudArgument;
import gg.moonrise.engine.paper.command.PaperCommand;
import gg.moonrise.hawkeye.config.permission.Permissions;
import gg.moonrise.hawkeye.config.provider.HawkeyeConfigProvider;
import gg.moonrise.moss.spring.Enableable;
import gg.moonrise.moss.spring.SpringComponent;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.plugin.java.JavaPlugin;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.annotations.AnnotationParser;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.paper.PaperCommandManager;
import org.incendo.cloud.parser.ParserDescriptor;
import org.incendo.cloud.parser.ParserRegistry;
import org.incendo.cloud.suggestion.FilteringSuggestionProcessor;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Registers every {@link PaperCommand} bean's Cloud annotations (and every {@link CloudArgument}
 * parser bean) with a Brigadier-backed {@link PaperCommandManager}. It replaces the plugin-engine
 * registry, which the shadow jar excludes because it runs handlers on Cloud's async coordinator:
 * here handlers run on the calling thread ({@code simpleCoordinator}), so commands that touch the
 * world need no extra hop, and commands with slow work schedule it themselves or return a future.
 * The root and subcommand aliases come from {@code commands.yml} ({@link CommandAliasProcessor}).
 * {@code verifyPluginJar} checks that this is the only command registry in the jar.
 */
@Slf4j
@SpringComponent
@RequiredArgsConstructor
public final class HawkeyeCommandRegistry implements Enableable {

    private final @NotNull JavaPlugin plugin;
    private final @NotNull HawkeyeConfigProvider configProvider;
    private final @NotNull ObjectProvider<PaperCommand> commands;
    private final @NotNull ObjectProvider<CloudArgument<?, ?>> arguments;
    private final AtomicReference<CommandManager<CommandSourceStack>> manager = new AtomicReference<>();

    @Override
    public void onEnable() {
        try {
            PaperCommandManager<CommandSourceStack> paper = PaperCommandManager.builder()
                    .executionCoordinator(ExecutionCoordinator.<CommandSourceStack>simpleCoordinator())
                    .buildOnEnable(plugin);
            int groups = register(paper);
            log.info("Registered {} command group(s)", groups);
        } catch (RuntimeException | LinkageError e) {
            // Cloud's Brigadier mapper reflects on server internals (and cannot run on MockBukkit).
            log.error("The commands could not be registered", e);
        }
    }

    /**
     * The command manager the commands were registered with.
     *
     * @return the manager, or empty before registration
     */
    public @NotNull Optional<CommandManager<CommandSourceStack>> manager() {
        return Optional.ofNullable(manager.get());
    }

    /**
     * Registers every argument parser and command bean with {@code target}.
     *
     * @param target the command manager
     * @return the number of registered command groups
     */
    public int register(@NotNull CommandManager<CommandSourceStack> target) {
        manager.set(target);
        // Cloud filters suggestions by prefix by default; every provider here matches with contains.
        target.suggestionProcessor(new FilteringSuggestionProcessor<>(
                FilteringSuggestionProcessor.Filter.<CommandSourceStack>contains(true)));
        registerArguments(target.parserRegistry());
        AnnotationParser<CommandSourceStack> parser = new AnnotationParser<>(target, CommandSourceStack.class);
        List<PaperCommand> beans = commands.orderedStream().toList();
        parser.stringProcessor(CommandAliasProcessor.of(configProvider.commands(), subcommandNames(beans)));
        AtomicInteger count = new AtomicInteger();
        beans.forEach(command -> {
            if (!command.shouldEnable()) {
                log.info("Skipped disabled command group {}", command.getClass().getSimpleName());
                return;
            }
            try {
                command.onRegister(target);
                parser.parse(command);
                count.incrementAndGet();
                log.debug("Registered command group {}", command.getClass().getSimpleName());
            } catch (RuntimeException e) {
                log.error("Could not register command group {}", command.getClass().getSimpleName(), e);
            }
        });
        return count.get();
    }

    /** The first word after the root of every {@code @Command} the beans declare, so aliases cannot shadow one. */
    private static @NotNull Set<String> subcommandNames(@NotNull List<PaperCommand> beans) {
        return beans.stream()
                .flatMap(bean -> Arrays.stream(bean.getClass().getMethods()))
                .map(method -> method.getAnnotation(Command.class))
                .filter(Objects::nonNull)
                .map(command -> command.value().split(" "))
                .filter(words -> words.length > 1 && words[0].equals(Permissions.ROOT))
                .map(words -> words[1])
                .collect(Collectors.toUnmodifiableSet());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void registerArguments(@NotNull ParserRegistry<CommandSourceStack> parsers) {
        arguments.orderedStream().forEach(argument -> {
            CloudArgument raw = argument;
            try {
                if (raw.name() == null) {
                    parsers.registerParser(ParserDescriptor.of(raw, raw.getType()));
                } else {
                    parsers.registerNamedParser(raw.name(), ParserDescriptor.of(raw, raw.getType()));
                }
            } catch (RuntimeException e) {
                log.error("Could not register argument parser {}", argument.getClass().getSimpleName(), e);
            }
        });
    }
}
