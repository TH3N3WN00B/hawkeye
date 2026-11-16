package gg.moonrise.hawkeye.command;

import gg.moonrise.engine.message.Message;
import gg.moonrise.engine.paper.command.PaperCommand;
import gg.moonrise.hawkeye.config.HawkeyeConfigProvider;
import gg.moonrise.hawkeye.config.ReloadResult;
import gg.moonrise.hawkeye.config.message.GeneralMessages;
import gg.moonrise.hawkeye.config.message.StatusMessages;
import gg.moonrise.hawkeye.core.service.status.StatusService;
import gg.moonrise.moss.spring.SpringComponent;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Permission;
import org.jetbrains.annotations.NotNull;

/** {@code /hawkeye}, {@code help}, {@code reload} and {@code status}. */
@Slf4j
@SpringComponent
@RequiredArgsConstructor
public final class HawkeyeCommand implements PaperCommand {

    private final @NotNull JavaPlugin plugin;
    private final @NotNull HawkeyeConfigProvider configProvider;
    private final @NotNull StatusService statusService;

    @Command(Permissions.ROOT)
    @CommandDescription("Shows the Hawkeye version")
    @Permission(Permissions.HELP)
    public void info(@NotNull CommandSourceStack source) {
        Message.of(general().getInfo()).send(source.getSender(),
                Placeholder.unparsed("name", plugin.getName()),
                Placeholder.unparsed("version", plugin.getPluginMeta().getVersion()));
    }

    @Command(Permissions.ROOT + " help")
    @CommandDescription("Lists the Hawkeye commands and parameters")
    @Permission(Permissions.HELP)
    public void help(@NotNull CommandSourceStack source) {
        general().getHelp().forEach(line -> Message.of(line).send(source.getSender()));
    }

    @Command(Permissions.ROOT + " reload")
    @CommandDescription("Reloads config.yml and messages.yml")
    @Permission(Permissions.ADMIN)
    public void reload(@NotNull CommandSourceStack source) {
        CommandSender sender = source.getSender();
        switch (configProvider.reloadConfig()) {
            case ReloadResult.Reloaded reloaded -> {
                Message.of(general().getReloaded()).send(sender);
                if (!reloaded.restartRequired().isEmpty()) {
                    Message.of(general().getReloadRestartRequired()).send(sender,
                            Placeholder.unparsed("settings", String.join(", ", reloaded.restartRequired())));
                }
            }
            case ReloadResult.Failed failed -> {
                log.warn("Reload requested by {} failed: {}", sender.getName(), failed.reason());
                Message.of(general().getReloadFailed()).send(sender, Placeholder.unparsed("reason", failed.reason()));
            }
        }
    }

    @Command(Permissions.ROOT + " status")
    @CommandDescription("Shows the queue, storage and writer health")
    @Permission(Permissions.ADMIN)
    public void status(@NotNull CommandSourceStack source) {
        StatusService.Status status = statusService.status();
        StatusMessages messages = configProvider.messages().getStatus();
        StatusService.Counters writer = status.counters();
        int node = status.health() instanceof StatusService.Health.Ready ready ? ready.node() : 0;
        Component state = switch (status.health()) {
            case StatusService.Health.Ready ready -> Message.of(messages.getStateReady()).asComponent();
            case StatusService.Health.Connecting connecting -> Message.of(messages.getStateConnecting()).asComponent();
            case StatusService.Health.Failed failed -> Message.of(messages.getStateFailed())
                    .asComponent(Placeholder.unparsed("reason", failed.reason()));
            case StatusService.Health.Incompatible incompatible -> Message.of(messages.getStateFailed())
                    .asComponent(Placeholder.unparsed("reason", "schema version " + incompatible.version()));
        };
        messages.getStatus().forEach(line -> Message.of(line).send(source.getSender(),
                Placeholder.unparsed("version", plugin.getPluginMeta().getVersion()),
                Placeholder.unparsed("server", status.server()),
                Placeholder.unparsed("node", Integer.toString(node)),
                Placeholder.unparsed("storage", status.storage()),
                Placeholder.component("state", state),
                Placeholder.unparsed("queued", Integer.toString(writer.queued())),
                Placeholder.unparsed("written", Long.toString(writer.written())),
                Placeholder.unparsed("dropped", Long.toString(writer.dropped())),
                Placeholder.unparsed("batches", Long.toString(writer.batches())),
                Placeholder.unparsed("last-flush-ms", Long.toString(writer.lastFlushMillis())),
                Placeholder.unparsed("spooled", Long.toString(writer.spooled()))));
    }

    private @NotNull GeneralMessages general() {
        return configProvider.messages().getGeneral();
    }
}
