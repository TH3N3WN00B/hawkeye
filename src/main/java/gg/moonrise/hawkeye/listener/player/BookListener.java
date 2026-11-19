package gg.moonrise.hawkeye.listener.player;

import gg.moonrise.hawkeye.config.file.LogCategory;
import gg.moonrise.hawkeye.core.service.capture.Actors;
import gg.moonrise.hawkeye.core.service.capture.EventRecorder;
import gg.moonrise.hawkeye.core.service.capture.text.BookText;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.moss.spring.SpringComponent;
import lombok.RequiredArgsConstructor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Books and quills written or signed ({@link LogCategory#BOOKS}), with the text of the book after the
 * edit ({@link BookText}), where the player stands.
 */
@SpringComponent
@RequiredArgsConstructor
public final class BookListener implements Listener {

    private final @NotNull EventRecorder recorder;

    /**
     * Records a book edit.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEdit(@NotNull PlayerEditBookEvent event) {
        Player player = event.getPlayer();
        if (recorder.enabled(LogCategory.BOOKS, player.getWorld())) {
            recorder.message(Action.BOOK_EDIT, Actors.of(player), player.getLocation(),
                    BookText.recorded(event.getNewBookMeta(), event.isSigning()));
        }
    }
}
