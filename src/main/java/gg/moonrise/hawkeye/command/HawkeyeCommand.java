package gg.moonrise.hawkeye.command;

import gg.moonrise.engine.message.Message;
import gg.moonrise.engine.paper.command.PaperCommand;
import gg.moonrise.hawkeye.config.HawkeyeConfig;
import gg.moonrise.hawkeye.config.HawkeyeConfigProvider;
import gg.moonrise.hawkeye.config.ReloadResult;
import gg.moonrise.hawkeye.core.service.greeting.GreetingResult;
import gg.moonrise.hawkeye.core.service.greeting.GreetingService;
import gg.moonrise.hawkeye.util.Completions;
import gg.moonrise.moss.spring.SpringComponent;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Permission;
import org.incendo.cloud.annotations.suggestion.Suggestions;
import org.incendo.cloud.context.CommandContext;
import org.jetbrains.annotations.NotNull;

/**
 * {@code /hawkeye}, {@code /hawkeye reload} and {@code /hawkeye greet <player>}. Command names and
 * permission nodes are contracts with operators: change them only as an explicit breaking change,
 * together with {@code paper-plugin.yml} and the README.
 */
@Slf4j
@SpringComponent
@RequiredArgsConstructor
public final class HawkeyeCommand implements PaperCommand {

    /** The root command. */
    public static final String ROOT = "hawkeye";

    /** Every subcommand; declared in {@code paper-plugin.yml}. */
    public static final String PERMISSION_ADMIN = "hawkeye.admin";

    /** Suggestions of online player names. */
    public static final String ONLINE_PLAYERS = "hawkeye-online-players";

    private final @NotNull JavaPlugin plugin;
    private final @NotNull HawkeyeConfigProvider configProvider;
    private final @NotNull GreetingService greetingService;

    @Command(ROOT)
    @CommandDescription("Shows the plugin name and version")
    @Permission(PERMISSION_ADMIN)
    public void info(@NotNull CommandSourceStack source) {
        Message.of(messages().getInfo()).send(source.getSender(),
                Placeholder.unparsed("name", plugin.getName()),
                Placeholder.unparsed("version", plugin.getPluginMeta().getVersion()));
    }

    @Command(ROOT + " reload")
    @CommandDescription("Reloads config.yml")
    @Permission(PERMISSION_ADMIN)
    public void reload(@NotNull CommandSourceStack source) {
        CommandSender sender = source.getSender();
        switch (configProvider.reloadConfig()) {
            case ReloadResult.Reloaded _ -> Message.of(messages().getReloaded()).send(sender);
            case ReloadResult.Failed failed -> {
                log.warn("Reload requested by {} failed: {}", sender.getName(), failed.reason());
                Message.of(messages().getReloadFailed()).send(sender, Placeholder.unparsed("reason", failed.reason()));
            }
        }
    }

    @Command(ROOT + " greet <player>")
    @CommandDescription("Sends the welcome-back greeting to an online player")
    @Permission(PERMISSION_ADMIN)
    public void greet(@NotNull CommandSourceStack source,
            @Argument(value = "player", suggestions = ONLINE_PLAYERS) @NotNull String name) {
        CommandSender sender = source.getSender();
        Player target = plugin.getServer().getPlayerExact(name);
        if (target == null) {
            Message.of(messages().getPlayerNotFound()).send(sender, Placeholder.unparsed("player", name));
            return;
        }
        switch (greetingService.greetingFor(false)) {
            case GreetingResult.Greet greet -> {
                greet.message().send(target, Placeholder.unparsed("player", target.getName()));
                Message.of(messages().getGreeted()).send(sender, Placeholder.unparsed("player", target.getName()));
            }
            case GreetingResult.Disabled _ -> Message.of(messages().getGreetingsDisabled()).send(sender);
        }
    }

    @Suggestions(ONLINE_PLAYERS)
    public @NotNull List<String> onlinePlayers(@NotNull CommandContext<CommandSourceStack> context,
            @NotNull String input) {
        List<String> names = plugin.getServer().getOnlinePlayers().stream().map(Player::getName).toList();
        return Completions.containing(names, input);
    }

    private @NotNull HawkeyeConfig.Messages messages() {
        return configProvider.get().getMessages();
    }
}
