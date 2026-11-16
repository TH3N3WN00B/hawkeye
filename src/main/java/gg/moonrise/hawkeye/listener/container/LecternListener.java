package gg.moonrise.hawkeye.listener.container;

import gg.moonrise.hawkeye.config.file.LogCategory;
import gg.moonrise.hawkeye.core.service.capture.Actors;
import gg.moonrise.hawkeye.core.service.capture.EventRecorder;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.moss.spring.SpringComponent;
import io.papermc.paper.event.player.PlayerInsertLecternBookEvent;
import lombok.RequiredArgsConstructor;
import org.bukkit.block.Block;
import org.bukkit.block.Lectern;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTakeLecternBookEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * Books put on and taken from lecterns ({@link LogCategory#CONTAINERS}): a deposit or withdrawal of
 * the book by the player at the lectern. A lectern's menu is not a container session
 * ({@code ContainerListener} skips it), so these two events are its only record.
 */
@SpringComponent
@RequiredArgsConstructor
public final class LecternListener implements Listener {

    private final @NotNull EventRecorder recorder;

    /**
     * Records a book put on a lectern.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInsert(@NotNull PlayerInsertLecternBookEvent event) {
        Block block = event.getBlock();
        if (recorder.enabled(LogCategory.CONTAINERS, block.getWorld())) {
            recorder.container(Action.CONTAINER_DEPOSIT, Actors.of(event.getPlayer()), block.getLocation(),
                    event.getBook(), 1);
        }
    }

    /**
     * Records a book taken from a lectern.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTake(@NotNull PlayerTakeLecternBookEvent event) {
        Player player = event.getPlayer();
        Lectern lectern = event.getLectern();
        ItemStack book = event.getBook();
        if (book != null && recorder.enabled(LogCategory.CONTAINERS, lectern.getWorld())) {
            recorder.container(Action.CONTAINER_WITHDRAW, Actors.of(player), lectern.getLocation(), book,
                    book.getAmount());
        }
    }
}
