package gg.moonrise.hawkeye.listener.player;

import gg.moonrise.hawkeye.config.file.LogCategory;
import gg.moonrise.hawkeye.core.service.capture.Actors;
import gg.moonrise.hawkeye.core.service.capture.EventRecorder;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.moss.spring.SpringComponent;
import lombok.RequiredArgsConstructor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

/** Joins and quits ({@link LogCategory#SESSIONS}) at the player's position. */
@SpringComponent
@RequiredArgsConstructor
public final class SessionListener implements Listener {

    private final @NotNull EventRecorder recorder;

    /**
     * Records a join.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(@NotNull PlayerJoinEvent event) {
        record(Action.JOIN, event.getPlayer());
    }

    /**
     * Records a quit.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onQuit(@NotNull PlayerQuitEvent event) {
        record(Action.QUIT, event.getPlayer());
    }

    private void record(@NotNull Action action, @NotNull Player player) {
        if (recorder.enabled(LogCategory.SESSIONS, player.getWorld())) {
            recorder.session(action, Actors.of(player), player.getLocation());
        }
    }
}
