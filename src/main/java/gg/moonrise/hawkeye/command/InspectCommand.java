package gg.moonrise.hawkeye.command;

import gg.moonrise.engine.message.Message;
import gg.moonrise.engine.paper.command.PaperCommand;
import gg.moonrise.hawkeye.config.message.InspectMessages;
import gg.moonrise.hawkeye.config.permission.Permissions;
import gg.moonrise.hawkeye.config.provider.HawkeyeConfigProvider;
import gg.moonrise.hawkeye.core.service.inspect.InspectorService;
import gg.moonrise.moss.spring.SpringComponent;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import lombok.RequiredArgsConstructor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Permission;
import org.jetbrains.annotations.NotNull;

/** {@code /hawkeye inspect}: turns the inspector on or off ({@code listener.inspect.InspectorListener}). */
@SpringComponent
@RequiredArgsConstructor
public final class InspectCommand implements PaperCommand {

    private final @NotNull InspectorService inspector;
    private final @NotNull HawkeyeConfigProvider configProvider;

    @Command(Permissions.ROOT + " inspect|i")
    @CommandDescription("Toggles the inspector")
    @Permission(Permissions.INSPECT)
    public void inspect(@NotNull CommandSourceStack source) {
        CommandSender sender = source.getSender();
        if (!(sender instanceof Player player)) {
            Message.of(configProvider.messages().getGeneral().getPlayersOnly()).send(sender);
            return;
        }
        InspectMessages messages = configProvider.messages().getInspect();
        boolean on = inspector.toggle(player.getUniqueId());
        Message.of(on ? messages.getEnabled() : messages.getDisabled()).send(player);
    }
}
