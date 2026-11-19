package gg.moonrise.hawkeye.listener.player;

import gg.moonrise.hawkeye.config.file.LogCategory;
import gg.moonrise.hawkeye.core.service.capture.Actors;
import gg.moonrise.hawkeye.core.service.capture.EventRecorder;
import gg.moonrise.hawkeye.core.service.capture.text.PlainText;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.payload.Payload;
import gg.moonrise.moss.spring.SpringComponent;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Joins and quits ({@link LogCategory#SESSIONS}) at the player's position. A kick is followed by the
 * player's quit, so its reason is remembered until then and the quit carries
 * {@code kicked: <reason>} as its text.
 */
@SpringComponent
@RequiredArgsConstructor
public final class SessionListener implements Listener {

    private final @NotNull EventRecorder recorder;
    private final Map<UUID, String> kicks = new ConcurrentHashMap<>();

    /**
     * Records a join.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(@NotNull PlayerJoinEvent event) {
        Player player = event.getPlayer();
        kicks.remove(player.getUniqueId());
        if (recorder.enabled(LogCategory.SESSIONS, player.getWorld())) {
            recorder.session(Action.JOIN, Actors.of(player), player.getLocation());
        }
    }

    /**
     * Remembers why a player is kicked, for their quit.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKick(@NotNull PlayerKickEvent event) {
        Player player = event.getPlayer();
        if (recorder.enabled(LogCategory.SESSIONS, player.getWorld())) {
            kicks.put(player.getUniqueId(), PlainText.oneLine(event.reason()));
        }
    }

    /**
     * Records a quit, with the kick reason when the player was kicked.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onQuit(@NotNull PlayerQuitEvent event) {
        Player player = event.getPlayer();
        String kick = kicks.remove(player.getUniqueId());
        if (!recorder.enabled(LogCategory.SESSIONS, player.getWorld())) {
            return;
        }
        Location location = player.getLocation();
        if (kick == null) {
            recorder.session(Action.QUIT, Actors.of(player), location);
            return;
        }
        String text = kick.isEmpty() ? "kicked" : "kicked: " + kick;
        recorder.record(Action.QUIT, Actors.of(player), location.getWorld(), location.getBlockX(),
                location.getBlockY(), location.getBlockZ(), "", null, 0, new Payload.Text(text));
    }

    /**
     * The kick reasons waiting for their player's quit.
     *
     * @return the count
     */
    public int pendingKicks() {
        return kicks.size();
    }
}
