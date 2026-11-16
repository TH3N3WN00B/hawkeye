package gg.moonrise.hawkeye.listener.player;

import gg.moonrise.hawkeye.config.file.LogCategory;
import gg.moonrise.hawkeye.core.service.capture.Actors;
import gg.moonrise.hawkeye.core.service.capture.EventRecorder;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.moss.spring.SpringComponent;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Game mode changes ({@link LogCategory#GAMEMODES}) as {@code <new mode> (<cause>)} in lower case,
 * for example {@code creative (command)}, where the player stands.
 */
@SpringComponent
@RequiredArgsConstructor
public final class GameModeListener implements Listener {

    private final @NotNull EventRecorder recorder;

    /**
     * Records a game mode change.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChange(@NotNull PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        if (recorder.enabled(LogCategory.GAMEMODES, player.getWorld())) {
            String text = event.getNewGameMode().name().toLowerCase(Locale.ROOT) + " ("
                    + event.getCause().name().toLowerCase(Locale.ROOT) + ")";
            recorder.message(Action.GAMEMODE_CHANGE, Actors.of(player), player.getLocation(), text);
        }
    }
}
