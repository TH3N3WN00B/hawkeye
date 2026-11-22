package gg.moonrise.hawkeye.listener.container;

import gg.moonrise.hawkeye.config.file.LogCategory;
import gg.moonrise.hawkeye.core.service.capture.Actors;
import gg.moonrise.hawkeye.core.service.capture.EventRecorder;
import gg.moonrise.hawkeye.core.service.capture.container.ContainerLocations;
import gg.moonrise.hawkeye.core.service.capture.container.InventoryDiff;
import gg.moonrise.hawkeye.core.service.thread.ServerThreads;
import gg.moonrise.moss.spring.SpringComponent;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import org.bukkit.Location;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Players moving items into and out of containers ({@link LogCategory#CONTAINERS}): container blocks and
 * the entities that carry one ({@link ContainerLocations}: minecarts with a chest or hopper, chest boats,
 * donkeys, mules and llamas, recorded at the block the entity stands in when it is opened). Opening a
 * container starts a session with a copy of its contents. A click or drag changes the contents
 * only after the event, so it schedules a comparison on the player's thread a tick later; closing
 * compares once more and ends the session. Each comparison records the net change per kind of item
 * ({@link InventoryDiff}) as a deposit or withdrawal at the container (the left half of a double
 * chest) and makes the current contents the new snapshot, also for everyone else viewing the same
 * container, so a change is never recorded twice.
 *
 * <p>Hoppers, hopper minecarts and droppers move items in and out while a player looks. Their events
 * fire before the server moves anything, so at that moment the difference from the snapshot is still
 * the viewer's own and is recorded; from then on the session is marked automatic. The viewer's next
 * click or drag (fired before the click changes anything), its comparison and the close then take the
 * current contents as the new snapshot without recording, so the transfer is never the player's and
 * only what really moved is skipped, however much of the item fitted. These events run on the
 * container's thread, which on Folia is the region of every viewer, since a viewer stands within reach.
 */
@SpringComponent
@RequiredArgsConstructor
public final class ContainerListener implements Listener {

    private final @NotNull EventRecorder recorder;
    private final @NotNull ServerThreads threads;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    /**
     * Starts a session for a container.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(@NotNull InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        UUID id = player.getUniqueId();
        if (!recorder.enabled(LogCategory.CONTAINERS, player.getWorld())) {
            sessions.remove(id);
            return;
        }
        Inventory top = event.getInventory();
        ContainerLocations.of(top).ifPresentOrElse(
                location -> sessions.put(id, new Session(player, location, top, InventoryDiff.copy(top.getContents()))),
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
     * Settles the sessions at both ends of an automatic transfer before it happens.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(@NotNull InventoryMoveItemEvent event) {
        if (sessions.isEmpty()) {
            return;
        }
        ContainerLocations.of(event.getSource()).ifPresent(this::automatic);
        ContainerLocations.of(event.getDestination()).ifPresent(this::automatic);
    }

    /**
     * Settles the sessions at a hopper before it picks up a ground item.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(@NotNull InventoryPickupItemEvent event) {
        if (!sessions.isEmpty()) {
            ContainerLocations.of(event.getInventory()).ifPresent(this::automatic);
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
     * @return players with a container open
     */
    public int sessions() {
        return sessions.size();
    }

    private void schedule(@NotNull HumanEntity viewer) {
        if (!(viewer instanceof Player player)) {
            return;
        }
        Session session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        if (session.automatic) {
            session.automatic = false;
            session.snapshot = InventoryDiff.copy(session.inventory.getContents());
        }
        if (!session.pending.compareAndSet(false, true)) {
            return;
        }
        threads.runFor(player, () -> {
            session.pending.set(false);
            if (sessions.get(player.getUniqueId()) == session) {
                compare(player, session);
            }
        });
    }

    private void automatic(@NotNull Location location) {
        for (Session session : sessions.values()) {
            if (session.location.equals(location)) {
                compare(session.player, session);
                session.automatic = true;
            }
        }
    }

    private void compare(@NotNull Player player, @NotNull Session session) {
        ItemStack[] current = InventoryDiff.copy(session.inventory.getContents());
        if (session.automatic) {
            session.snapshot = current;
            return;
        }
        List<InventoryDiff.Delta> deltas = InventoryDiff.diff(session.snapshot, current);
        session.snapshot = current;
        if (deltas.isEmpty()) {
            return;
        }
        for (Session other : sessions.values()) {
            if (other != session && other.location.equals(session.location)) {
                other.snapshot = current;
            }
        }
        if (session.location.getWorld() == null
                || !recorder.enabled(LogCategory.CONTAINERS, session.location.getWorld())) {
            return;
        }
        recorder.containerChanges(Actors.of(player), session.location, deltas);
    }

    /**
     * One player's view of one container. The snapshot is read and replaced on the thread that owns
     * the container (the viewers' region). While {@code automatic} is set, the contents changed since
     * the snapshot were moved by a hopper or dropper, not by the viewer.
     */
    private static final class Session {

        private final Player player;
        private final Location location;
        private final Inventory inventory;
        private final AtomicBoolean pending = new AtomicBoolean();
        private volatile @Nullable ItemStack @NotNull [] snapshot;
        private volatile boolean automatic;

        private Session(@NotNull Player player, @NotNull Location location, @NotNull Inventory inventory,
                @Nullable ItemStack @NotNull [] snapshot) {
            this.player = player;
            this.location = location;
            this.inventory = inventory;
            this.snapshot = snapshot;
        }
    }
}
