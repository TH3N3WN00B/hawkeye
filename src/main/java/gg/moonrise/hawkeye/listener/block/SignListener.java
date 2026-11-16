package gg.moonrise.hawkeye.listener.block;

import gg.moonrise.hawkeye.config.file.LogCategory;
import gg.moonrise.hawkeye.core.service.capture.Actors;
import gg.moonrise.hawkeye.core.service.capture.EventRecorder;
import gg.moonrise.hawkeye.core.service.capture.SignText;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.payload.Payload;
import gg.moonrise.hawkeye.entity.payload.SignSides;
import gg.moonrise.moss.spring.SpringComponent;
import lombok.RequiredArgsConstructor;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Sign text ({@link LogCategory#SIGNS}): an edit is {@link Action#SIGN_EDIT} with both sides before and
 * after, so a rollback can put the old text back. Closing the editor without a change is ignored.
 */
@SpringComponent
@RequiredArgsConstructor
public final class SignListener implements Listener {

    private final @NotNull EventRecorder recorder;

    /**
     * Records the edited side; the other side is kept in both snapshots.
     *
     * @param event the event, raised before the new text is applied
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEdit(@NotNull SignChangeEvent event) {
        Block block = event.getBlock();
        if (!recorder.enabled(LogCategory.SIGNS, block.getWorld())) {
            return;
        }
        SignSides before = block.getState(false) instanceof Sign sign ? SignText.sides(sign) : SignSides.EMPTY;
        SignSides after = SignText.with(before, event.getSide(), SignText.lines(event.lines()));
        if (after.equals(before)) {
            return;
        }
        BlockData state = block.getBlockData();
        recorder.block(Action.SIGN_EDIT, Actors.of(event.getPlayer()), block, state, state,
                new Payload.Sign(before, after));
    }
}
