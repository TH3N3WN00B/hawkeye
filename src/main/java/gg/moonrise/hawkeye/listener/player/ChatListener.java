package gg.moonrise.hawkeye.listener.player;

import gg.moonrise.hawkeye.config.HawkeyeConfigProvider;
import gg.moonrise.hawkeye.config.file.LogCategory;
import gg.moonrise.hawkeye.core.service.capture.Actors;
import gg.moonrise.hawkeye.core.service.capture.EventRecorder;
import gg.moonrise.hawkeye.core.service.capture.text.CommandText;
import gg.moonrise.hawkeye.core.service.thread.ServerThreads;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.moss.spring.SpringComponent;
import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.event.player.PlayerSignCommandPreprocessEvent;
import java.util.List;
import lombok.RequiredArgsConstructor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.World;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Chat messages ({@link LogCategory#CHAT}) as plain text, and commands ({@link LogCategory#COMMANDS})
 * as typed, or only {@code /label} for the redacted commands. Chat arrives off the player's thread,
 * so it is recorded from the player's thread a tick later, where the player's position may be read.
 * A command run by clicking a sign (often with permissions the player lacks) is marked
 * {@value #SIGN_PREFIX}; commands typed into the server console ({@link LogCategory#CONSOLE_COMMANDS},
 * not command blocks) are recorded by {@link Actors#CONSOLE} at the first world's spawn.
 */
@SpringComponent
@RequiredArgsConstructor
public final class ChatListener implements Listener {

    /** What precedes the command line of a command run from a sign. */
    public static final String SIGN_PREFIX = "[sign] ";

    private final @NotNull EventRecorder recorder;
    private final @NotNull ServerThreads threads;
    private final @NotNull HawkeyeConfigProvider configProvider;

    /**
     * Hands a chat message to the player's thread.
     *
     * @param event the event, on a chat thread
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(@NotNull AsyncChatEvent event) {
        if (!configProvider.get().logging().enabled(LogCategory.CHAT)) {
            return;
        }
        Player player = event.getPlayer();
        String text = PlainTextComponentSerializer.plainText().serialize(event.message());
        threads.runFor(player, () -> {
            if (recorder.enabled(LogCategory.CHAT, player.getWorld())) {
                recorder.message(Action.CHAT, Actors.of(player), player.getLocation(), text);
            }
        });
    }

    /**
     * Records a command. {@link PlayerSignCommandPreprocessEvent} shares this event's handler list, so
     * commands run from signs arrive here too.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(@NotNull PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (recorder.enabled(LogCategory.COMMANDS, player.getWorld())) {
            String line = CommandText.recorded(event.getMessage(), configProvider.get().logging());
            recorder.message(Action.COMMAND, Actors.of(player), player.getLocation(),
                    event instanceof PlayerSignCommandPreprocessEvent ? SIGN_PREFIX + line : line);
        }
    }

    /**
     * Records a command typed into the server console. Command blocks and command minecarts send this
     * event too and are skipped; remote console (RCON) commands have an event of their own.
     *
     * @param event the event, on the global region
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsoleCommand(@NotNull ServerCommandEvent event) {
        if (!(event.getSender() instanceof ConsoleCommandSender console)) {
            return;
        }
        List<World> worlds = console.getServer().getWorlds();
        if (worlds.isEmpty() || !recorder.enabled(LogCategory.CONSOLE_COMMANDS, worlds.getFirst())) {
            return;
        }
        recorder.message(Action.COMMAND, Actors.CONSOLE, worlds.getFirst().getSpawnLocation(),
                CommandText.recorded(event.getCommand(), configProvider.get().logging()));
    }
}
