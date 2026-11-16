package gg.moonrise.hawkeye.listener.block;

import gg.moonrise.hawkeye.config.file.LogCategory;
import gg.moonrise.hawkeye.core.service.capture.Actors;
import gg.moonrise.hawkeye.core.service.capture.EventRecorder;
import gg.moonrise.hawkeye.core.service.thread.ServerThreads;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.moss.spring.SpringComponent;
import lombok.RequiredArgsConstructor;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Block uses ({@link LogCategory#INTERACTIONS}): a right click on a door, trapdoor, fence gate, lever,
 * button, repeater, comparator, note block or daylight detector. The world applies the use after the
 * event, so the state is read again on the block's thread a tick later and {@link Action#INTERACT} is
 * recorded only when it changed (an iron door does not open by hand).
 */
@SpringComponent
@RequiredArgsConstructor
public final class InteractionListener implements Listener {

    private final @NotNull EventRecorder recorder;
    private final @NotNull ServerThreads threads;

    /**
     * Remembers the state and compares it next tick.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(@NotNull PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null || event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK
                || !recorder.enabled(LogCategory.INTERACTIONS, block.getWorld()) || !isTracked(block.getType())) {
            return;
        }
        String before = block.getBlockData().getAsString();
        Actor actor = Actors.of(event.getPlayer());
        World world = block.getWorld();
        threads.runAtChunk(world, block.getX() >> 4, block.getZ() >> 4, () -> {
            String after = block.getBlockData().getAsString();
            if (!after.equals(before)) {
                recorder.record(Action.INTERACT, actor, world, block.getX(), block.getY(), block.getZ(), after, before,
                        0, null);
            }
        });
    }

    private static boolean isTracked(@NotNull Material material) {
        return switch (material) {
            case LEVER, REPEATER, COMPARATOR, NOTE_BLOCK, DAYLIGHT_DETECTOR -> true;
            default -> Tag.DOORS.isTagged(material) || Tag.TRAPDOORS.isTagged(material)
                    || Tag.FENCE_GATES.isTagged(material) || Tag.BUTTONS.isTagged(material);
        };
    }
}
