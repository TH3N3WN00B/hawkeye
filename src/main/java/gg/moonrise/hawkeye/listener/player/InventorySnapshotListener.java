package gg.moonrise.hawkeye.listener.player;

import gg.moonrise.hawkeye.config.file.LogCategory;
import gg.moonrise.hawkeye.core.service.capture.EventRecorder;
import gg.moonrise.hawkeye.core.service.capture.text.PlainText;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.moss.spring.SpringComponent;
import java.time.Clock;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.jetbrains.annotations.NotNull;

/**
 * Copies of a player's whole inventory when they join ({@link LogCategory#JOIN_SNAPSHOTS}), leave
 * ({@link LogCategory#QUIT_SNAPSHOTS}), teleport or change worlds ({@link LogCategory#TELEPORT_SNAPSHOTS})
 * and die ({@link LogCategory#DEATH_SNAPSHOTS}; Paper clears the inventory only after the death event).
 *
 * <p>A teleport is recorded where the player left from, with {@code <cause> to <world> <x> <y> <z>} as its
 * detail. Position corrections ({@code UNKNOWN}), dismounts and leaving a bed are not travel and are
 * skipped, and within one world a teleport less than {@value #TELEPORT_INTERVAL_MILLIS} ms after the
 * player's previous teleport snapshot is skipped too (anti-cheat setbacks, plugins teleporting every tick).
 * A world change that no recorded teleport announced (a respawn in another world, a plugin moving the
 * player without a teleport event) is recorded where the player arrived, with
 * {@code world_change from <world>}. Portal travel is recorded from its {@link PlayerTeleportEvent} (cause
 * {@code nether_portal}, {@code end_portal}, {@code end_gateway}), which Paper fires for every portal trip
 * after the {@code PlayerPortalEvent}, so the portal event itself is not handled (it would record each trip
 * twice).
 */
@SpringComponent
@RequiredArgsConstructor
public final class InventorySnapshotListener implements Listener {

    /** The shortest time between two teleport snapshots of a player within one world. */
    public static final long TELEPORT_INTERVAL_MILLIS = 1_000;

    /** The longest death message kept. */
    public static final int MAX_DEATH_MESSAGE = 256;

    private static final Set<TeleportCause> NOT_TRAVEL = EnumSet.of(TeleportCause.UNKNOWN, TeleportCause.DISMOUNT,
            TeleportCause.EXIT_BED);

    private final @NotNull EventRecorder recorder;
    private final @NotNull Clock clock;
    private final Map<UUID, Long> lastTeleports = new ConcurrentHashMap<>();
    private final Map<UUID, String> announcedWorlds = new ConcurrentHashMap<>();

    /**
     * Records the inventory a player joins with.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(@NotNull PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (recorder.enabled(LogCategory.JOIN_SNAPSHOTS, player.getWorld())) {
            recorder.snapshot(Action.SNAPSHOT_JOIN, player, player.getLocation(), "");
        }
    }

    /**
     * Records the inventory a player leaves with, and forgets the player.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onQuit(@NotNull PlayerQuitEvent event) {
        Player player = event.getPlayer();
        lastTeleports.remove(player.getUniqueId());
        announcedWorlds.remove(player.getUniqueId());
        if (recorder.enabled(LogCategory.QUIT_SNAPSHOTS, player.getWorld())) {
            recorder.snapshot(Action.SNAPSHOT_QUIT, player, player.getLocation(), "");
        }
    }

    /**
     * Records the inventory a player died with, before it drops, with the death message.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(@NotNull PlayerDeathEvent event) {
        Player player = event.getPlayer();
        if (!recorder.enabled(LogCategory.DEATH_SNAPSHOTS, player.getWorld())) {
            return;
        }
        Component message = event.deathMessage();
        String detail = message == null ? "" : PlainText.capped(PlainText.oneLine(message), MAX_DEATH_MESSAGE);
        recorder.snapshot(Action.SNAPSHOT_DEATH, player, player.getLocation(), detail);
    }

    /**
     * Records the inventory a player teleports with (portals and end gateways included).
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(@NotNull PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        Location from = event.getFrom();
        Location to = event.getTo();
        World fromWorld = from.getWorld();
        if (fromWorld == null || NOT_TRAVEL.contains(event.getCause())) {
            return;
        }
        World toWorld = to.getWorld() == null ? fromWorld : to.getWorld();
        boolean otherWorld = !toWorld.getName().equals(fromWorld.getName());
        // A marker from an earlier teleport that never arrived is stale now.
        announcedWorlds.remove(id);
        if (!recorder.enabled(LogCategory.TELEPORT_SNAPSHOTS, fromWorld)) {
            return;
        }
        long now = clock.millis();
        Long last = lastTeleports.get(id);
        if (!otherWorld && last != null && now - last >= 0 && now - last < TELEPORT_INTERVAL_MILLIS) {
            return;
        }
        String detail = event.getCause().name().toLowerCase(Locale.ROOT) + " to " + toWorld.getName() + " "
                + to.getBlockX() + " " + to.getBlockY() + " " + to.getBlockZ();
        recorder.snapshot(Action.SNAPSHOT_TELEPORT, player, from, detail);
        lastTeleports.put(id, now);
        if (otherWorld) {
            announcedWorlds.put(id, toWorld.getName());
        }
    }

    /**
     * Records the inventory of a player who changed worlds without a recorded teleport.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldChange(@NotNull PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        World world = player.getWorld();
        String announced = announcedWorlds.remove(player.getUniqueId());
        if (world.getName().equals(announced) || !recorder.enabled(LogCategory.TELEPORT_SNAPSHOTS, world)) {
            return;
        }
        recorder.snapshot(Action.SNAPSHOT_TELEPORT, player, player.getLocation(),
                "world_change from " + event.getFrom().getName());
        lastTeleports.put(player.getUniqueId(), clock.millis());
    }

    /**
     * The players with a remembered teleport or world change.
     *
     * @return the count
     */
    public int trackedPlayers() {
        Set<UUID> players = new HashSet<>(lastTeleports.keySet());
        players.addAll(announcedWorlds.keySet());
        return players.size();
    }
}
