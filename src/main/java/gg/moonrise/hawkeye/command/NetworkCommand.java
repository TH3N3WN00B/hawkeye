package gg.moonrise.hawkeye.command;

import gg.moonrise.engine.message.Message;
import gg.moonrise.engine.paper.command.PaperCommand;
import gg.moonrise.hawkeye.config.HawkeyeConfigProvider;
import gg.moonrise.hawkeye.config.message.StatusMessages;
import gg.moonrise.hawkeye.core.service.network.NetworkService;
import gg.moonrise.hawkeye.core.service.thread.ServerThreads;
import gg.moonrise.hawkeye.util.Durations;
import gg.moonrise.moss.spring.SpringComponent;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Permission;
import org.jetbrains.annotations.NotNull;

/** {@code /hawkeye network}: every server of every shard and whether its heartbeat is recent. */
@SpringComponent
@RequiredArgsConstructor
public final class NetworkCommand implements PaperCommand {

    private final @NotNull NetworkService networkService;
    private final @NotNull HawkeyeConfigProvider configProvider;
    private final @NotNull ServerThreads threads;

    @Command(Permissions.ROOT + " network")
    @CommandDescription("Lists the servers of every shard")
    @Permission(Permissions.NETWORK)
    public void network(@NotNull CommandSourceStack source) {
        CommandSender sender = source.getSender();
        networkService.view().thenAccept(view -> threads.runFor(sender, () -> {
            StatusMessages messages = configProvider.messages().getStatus();
            Message.of(messages.getNetworkHeader()).send(sender);
            view.servers().forEach(server -> Message.of(messages.getNetworkServer()).send(sender,
                    Placeholder.unparsed("shard", server.shard()),
                    Placeholder.unparsed("server", server.name()),
                    Placeholder.unparsed("node", Integer.toString(server.node())),
                    Placeholder.unparsed("seen", Durations.format(Duration.ofMillis(server.silentMillis()))),
                    Placeholder.parsed("state", server.online() ? messages.getServerOnline()
                            : messages.getServerOffline())));
            view.failures().forEach((shard, reason) -> Message.of(configProvider.messages().getLookup()
                    .getShardUnavailable()).send(sender, Placeholder.unparsed("shard", shard),
                    Placeholder.unparsed("reason", reason)));
        }));
    }
}
