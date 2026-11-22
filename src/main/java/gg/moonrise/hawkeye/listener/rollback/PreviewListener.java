package gg.moonrise.hawkeye.listener.rollback;

import gg.moonrise.hawkeye.core.service.rollback.RollbackPreviews;
import gg.moonrise.moss.spring.SpringComponent;
import lombok.RequiredArgsConstructor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Ends a player's rollback preview when they leave: after a reconnect their client shows the real
 * blocks, so {@code /hawkeye apply} must not apply a plan they no longer see.
 */
@SpringComponent
@RequiredArgsConstructor
public final class PreviewListener implements Listener {

    private final @NotNull RollbackPreviews previews;

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(@NotNull PlayerQuitEvent event) {
        previews.discard(event.getPlayer().getUniqueId());
    }
}
