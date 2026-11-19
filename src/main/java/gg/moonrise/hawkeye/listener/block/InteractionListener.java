package gg.moonrise.hawkeye.listener.block;

import gg.moonrise.hawkeye.config.file.LogCategory;
import gg.moonrise.hawkeye.core.service.capture.Actors;
import gg.moonrise.hawkeye.core.service.capture.CauseTracker;
import gg.moonrise.hawkeye.core.service.capture.EventRecorder;
import gg.moonrise.hawkeye.core.service.capture.block.BlockCapture;
import gg.moonrise.hawkeye.core.service.thread.ServerThreads;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.moss.spring.SpringComponent;
import lombok.RequiredArgsConstructor;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Block uses ({@link LogCategory#INTERACTIONS}): a right click on a door, trapdoor, fence gate, lever,
 * button, repeater, comparator, note block or daylight detector. The world applies the use after the
 * event, so the state is read again on the block's thread a tick later and {@link Action#INTERACT} is
 * recorded only when it changed (an iron door does not open by hand). A dragon egg that teleports when
 * clicked is a break where it was and a place where it lands, by the player who clicked it or
 * {@code #dragon_egg}.
 */
@SpringComponent
@RequiredArgsConstructor
public final class InteractionListener implements Listener {

    /** A dragon egg teleporting with nobody known to have clicked it. */
    private static final Actor DRAGON_EGG = Actor.source("dragon_egg");

    private final @NotNull EventRecorder recorder;
    private final @NotNull BlockCapture blocks;
    private final @NotNull CauseTracker causes;
    private final @NotNull ServerThreads threads;

    /**
     * Remembers the state and compares it next tick; remembers who clicked a dragon egg.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(@NotNull PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null || !recorder.enabled(LogCategory.INTERACTIONS, block.getWorld())) {
            return;
        }
        if (block.getType() == Material.DRAGON_EGG) {
            // A left or a right click teleports it; the teleport itself names no player.
            causes.remember(block, Actors.of(event.getPlayer()));
            return;
        }
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK || !isTracked(block.getType())) {
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

    /**
     * Records a dragon egg teleporting, before it moves.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEggTeleport(@NotNull BlockFromToEvent event) {
        Block from = event.getBlock();
        if (from.getType() != Material.DRAGON_EGG || !recorder.enabled(LogCategory.INTERACTIONS, from.getWorld())) {
            return;
        }
        Actor actor = causes.recentCause(from).orElse(DRAGON_EGG);
        BlockData egg = from.getBlockData();
        Block to = event.getToBlock();
        blocks.broken(actor, from);
        recorder.block(Action.BLOCK_PLACE, actor, to, egg, to.getBlockData(), null);
    }

    private static boolean isTracked(@NotNull Material material) {
        return switch (material) {
            case LEVER, REPEATER, COMPARATOR, NOTE_BLOCK, DAYLIGHT_DETECTOR -> true;
            default -> Tag.DOORS.isTagged(material) || Tag.TRAPDOORS.isTagged(material)
                    || Tag.FENCE_GATES.isTagged(material) || Tag.BUTTONS.isTagged(material);
        };
    }
}
