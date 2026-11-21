package gg.moonrise.hawkeye.command;

import gg.moonrise.engine.message.Message;
import gg.moonrise.engine.paper.command.PaperCommand;
import gg.moonrise.hawkeye.config.message.GeneralMessages;
import gg.moonrise.hawkeye.config.permission.Permissions;
import gg.moonrise.hawkeye.config.provider.HawkeyeConfigProvider;
import gg.moonrise.hawkeye.core.service.thread.ServerThreads;
import gg.moonrise.hawkeye.util.text.Completions;
import gg.moonrise.moss.spring.SpringComponent;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.World;
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
 * {@code /hawkeye tp <world> <x> <y> <z>}: teleports to the centre of a block, keeping the player's
 * view direction. Result locations on this server run it when clicked.
 */
@Slf4j
@SpringComponent
@RequiredArgsConstructor
public final class TeleportCommand implements PaperCommand {

    /** The {@code @Suggestions} name of a world-name argument. */
    public static final String WORLDS = "hawkeye-worlds";

    private final @NotNull JavaPlugin plugin;
    private final @NotNull HawkeyeConfigProvider configProvider;
    private final @NotNull ServerThreads threads;

    @Command(Permissions.ROOT + " tp <world> <x> <y> <z>")
    @CommandDescription("Teleports to a block of a lookup result")
    @Permission(Permissions.TELEPORT)
    public void teleport(@NotNull CommandSourceStack source,
            @Argument(value = "world", suggestions = WORLDS) @NotNull String world,
            @Argument("x") int x, @Argument("y") int y, @Argument("z") int z) {
        CommandSender sender = source.getSender();
        GeneralMessages messages = configProvider.messages().getGeneral();
        if (!(sender instanceof Player player)) {
            Message.of(messages.getPlayersOnly()).send(sender);
            return;
        }
        World target = plugin.getServer().getWorld(world);
        if (target == null) {
            Message.of(messages.getUnknownWorld()).send(player, Placeholder.unparsed("world", world));
            return;
        }
        Location location = new Location(target, x + 0.5, y, z + 0.5, player.getYaw(), player.getPitch());
        player.teleportAsync(location).whenComplete((teleported, error) -> {
            if (error != null) {
                log.warn("Could not teleport {} to {} {} {} in {}", player.getName(), x, y, z, world, error);
                return;
            }
            if (Boolean.TRUE.equals(teleported)) {
                threads.runFor(player, () -> Message.of(configProvider.messages().getGeneral().getTeleported())
                        .send(player, Placeholder.unparsed("world", target.getName()),
                                Placeholder.unparsed("x", Integer.toString(x)),
                                Placeholder.unparsed("y", Integer.toString(y)),
                                Placeholder.unparsed("z", Integer.toString(z))));
            }
        });
    }

    @Suggestions(WORLDS)
    public @NotNull List<String> worlds(@NotNull CommandContext<CommandSourceStack> context, @NotNull String input) {
        return Completions.containing(plugin.getServer().getWorlds().stream().map(World::getName).toList(), input);
    }
}
