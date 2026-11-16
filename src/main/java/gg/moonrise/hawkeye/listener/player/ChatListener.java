package gg.moonrise.hawkeye.listener.player;

import gg.moonrise.hawkeye.config.HawkeyeConfigProvider;
import gg.moonrise.hawkeye.config.file.LogCategory;
import gg.moonrise.hawkeye.core.service.capture.Actors;
import gg.moonrise.hawkeye.core.service.capture.CommandText;
import gg.moonrise.hawkeye.core.service.capture.EventRecorder;
import gg.moonrise.hawkeye.core.service.thread.ServerThreads;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.moss.spring.SpringComponent;
import io.papermc.paper.event.player.AsyncChatEvent;
import lombok.RequiredArgsConstructor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Chat messages ({@link LogCategory#CHAT}) as plain text, and commands ({@link LogCategory#COMMANDS})
 * as typed, or only {@code /label} for the redacted commands. Chat arrives off the player's thread,
 * so it is recorded from the player's thread a tick later, where the player's position may be read.
 */
@SpringComponent
@RequiredArgsConstructor
public final class ChatListener implements Listener {

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
     * Records a command.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(@NotNull PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (recorder.enabled(LogCategory.COMMANDS, player.getWorld())) {
            recorder.message(Action.COMMAND, Actors.of(player), player.getLocation(),
                    CommandText.recorded(event.getMessage(), configProvider.get().logging()));
        }
    }
}
