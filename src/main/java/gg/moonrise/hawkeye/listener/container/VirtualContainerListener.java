package gg.moonrise.hawkeye.listener.container;

import gg.moonrise.hawkeye.config.file.LogCategory;
import gg.moonrise.hawkeye.core.service.capture.Actors;
import gg.moonrise.hawkeye.core.service.capture.EventRecorder;
import gg.moonrise.hawkeye.core.service.capture.container.InventoryDiff;
import gg.moonrise.hawkeye.core.service.capture.container.VirtualContainers;
import gg.moonrise.hawkeye.core.service.capture.container.VirtualTransfers;
import gg.moonrise.hawkeye.core.service.thread.ServerThreads;
import gg.moonrise.moss.spring.SpringComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Players moving items into and out of virtual containers ({@link LogCategory#VIRTUAL_CONTAINERS}):
 * ender chests, plugin inventories and other players' inventories ({@link VirtualContainers}), recorded
 * where the player stands. Container blocks and entities stay with {@link ContainerListener}.
 *
 * <p>Opening one starts a session with a copy of the inventory and of what the viewer holds (their
 * inventory and the cursor stack). A click or drag schedules a comparison on the player's thread a
 * tick later; closing compares once more and ends the session. A comparison records only the viewer's
 * own moves ({@link VirtualTransfers#match}): plugin menus change their own contents while clicks are
 * cancelled, so a change of the inventory counts only as far as what the viewer holds changed the
 * opposite way. An item dropped while the session is open (the drop key on a slot of the inventory, or
 * the cursor dropped outside the window) counts as still held at the next comparison, so a drop from
 * the inventory is a withdrawal and a drop of an item already taken out is not recorded twice.
 *
 * <p>Each session keeps its own copy of the inventory. When a viewer's moves are recorded they are
 * handed to the other sessions on the same inventory, which take them out of their next comparison, so
 * two viewers of a shared vault are each credited with exactly their own moves.
 */
@SpringComponent
@RequiredArgsConstructor
public final class VirtualContainerListener implements Listener {

    private final @NotNull EventRecorder recorder;
    private final @NotNull ServerThreads threads;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    /**
     * Starts a session for a virtual container.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(@NotNull InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        UUID id = player.getUniqueId();
        if (!recorder.enabled(LogCategory.VIRTUAL_CONTAINERS, player.getWorld())) {
            sessions.remove(id);
            return;
        }
        Inventory top = event.getInventory();
        VirtualContainers.label(event.getView(), player).ifPresentOrElse(
                label -> sessions.put(id, new Session(top, label, InventoryDiff.copy(top.getContents()),
                        held(player))),
                () -> sessions.remove(id));
    }

    /**
     * Schedules a comparison after a click.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(@NotNull InventoryClickEvent event) {
        schedule(event.getWhoClicked());
    }

    /**
     * Schedules a comparison after a drag.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrag(@NotNull InventoryDragEvent event) {
        schedule(event.getWhoClicked());
    }

    /**
     * Counts an item the viewer drops as still held until the next comparison.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(@NotNull PlayerDropItemEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null) {
            session.drop(event.getItemDrop().getItemStack());
        }
    }

    /**
     * Compares one last time and ends the session.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClose(@NotNull InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            Session session = sessions.remove(player.getUniqueId());
            if (session != null) {
                compare(player, session);
            }
        }
    }

    /**
     * Drops the session of a player who leaves.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onQuit(@NotNull PlayerQuitEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
    }

    /**
     * The number of open sessions.
     *
     * @return players with a virtual container open
     */
    public int sessions() {
        return sessions.size();
    }

    private void schedule(@NotNull HumanEntity viewer) {
        if (!(viewer instanceof Player player)) {
            return;
        }
        Session session = sessions.get(player.getUniqueId());
        if (session == null || !session.pending.compareAndSet(false, true)) {
            return;
        }
        threads.runFor(player, () -> {
            session.pending.set(false);
            if (sessions.get(player.getUniqueId()) == session) {
                compare(player, session);
            }
        });
    }

    private void compare(@NotNull Player player, @NotNull Session session) {
        List<InventoryDiff.Delta> moves = session.compare(InventoryDiff.copy(session.inventory.getContents()),
                held(player));
        if (moves.isEmpty()) {
            return;
        }
        for (Session other : sessions.values()) {
            if (other != session && other.inventory.equals(session.inventory)) {
                other.account(moves);
            }
        }
        if (recorder.enabled(LogCategory.VIRTUAL_CONTAINERS, player.getWorld())) {
            recorder.virtualChanges(Actors.of(player), player.getLocation(), session.label, moves);
        }
    }

    /** What a player holds: a copy of their inventory's contents and, after them, the cursor stack. */
    private static @Nullable ItemStack @NotNull [] held(@NotNull Player player) {
        ItemStack[] contents = player.getInventory().getContents();
        ItemStack[] held = new ItemStack[contents.length + 1];
        System.arraycopy(contents, 0, held, 0, contents.length);
        held[contents.length] = player.getItemOnCursor();
        return InventoryDiff.copy(held);
    }

    /**
     * One player's view of one virtual container. Its copies are read and replaced under the session's
     * lock: comparisons and drops run on the viewer's thread, but another viewer of the same inventory
     * (on Folia, possibly in another region) hands over its moves from its own.
     */
    private static final class Session {

        private final Inventory inventory;
        private final String label;
        private final AtomicBoolean pending = new AtomicBoolean();
        private final List<ItemStack> dropped = new ArrayList<>();
        private final List<InventoryDiff.Delta> accounted = new ArrayList<>();
        private @Nullable ItemStack @NotNull [] contents;
        private @Nullable ItemStack @NotNull [] held;

        private Session(@NotNull Inventory inventory, @NotNull String label,
                @Nullable ItemStack @NotNull [] contents, @Nullable ItemStack @NotNull [] held) {
            this.inventory = inventory;
            this.label = label;
            this.contents = contents;
            this.held = held;
        }

        private synchronized void drop(@NotNull ItemStack item) {
            if (!item.isEmpty()) {
                dropped.add(item.clone());
            }
        }

        private synchronized void account(@NotNull List<InventoryDiff.Delta> moves) {
            accounted.addAll(moves);
        }

        /**
         * The viewer's moves since the last comparison; the current copies become the new ones and the
         * dropped and accounted items are cleared.
         */
        private synchronized @NotNull List<InventoryDiff.Delta> compare(
                @Nullable ItemStack @NotNull [] currentContents, @Nullable ItemStack @NotNull [] currentHeld) {
            List<InventoryDiff.Delta> container = VirtualTransfers.without(
                    InventoryDiff.diff(contents, currentContents), accounted);
            ItemStack[] heldNow = new ItemStack[currentHeld.length + dropped.size()];
            System.arraycopy(currentHeld, 0, heldNow, 0, currentHeld.length);
            for (int index = 0; index < dropped.size(); index++) {
                heldNow[currentHeld.length + index] = dropped.get(index);
            }
            List<InventoryDiff.Delta> own = InventoryDiff.diff(held, heldNow);
            contents = currentContents;
            held = currentHeld;
            dropped.clear();
            accounted.clear();
            return VirtualTransfers.match(container, own);
        }
    }
}
