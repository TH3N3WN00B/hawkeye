package gg.moonrise.hawkeye.listener.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.core.service.capture.EventRecorder;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.entity.payload.Payload;
import gg.moonrise.hawkeye.listener.Recorded;
import gg.moonrise.hawkeye.support.TestHawkeyePlugin;
import gg.moonrise.hawkeye.support.TestPlugins;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

public class InventorySnapshotListenerTest {

    private ServerMock server;
    private TestHawkeyePlugin plugin;
    private World world;
    private World nether;
    private PlayerMock player;

    @BeforeEach
    public void setUp() {
        server = MockBukkit.mock();
        plugin = TestPlugins.load();
        world = server.addSimpleWorld("world");
        nether = server.addSimpleWorld("world_nether");
        player = server.addPlayer("Alex");
        player.setLocation(new Location(world, 4.5, 70, -8.5));
        player.getInventory().setItem(0, new ItemStack(Material.STONE, 64));
        player.getInventory().setItem(8, new ItemStack(Material.BREAD, 5));
        player.getInventory().setChestplate(new ItemStack(Material.IRON_CHESTPLATE));
        player.getInventory().setItemInOffHand(new ItemStack(Material.SHIELD));
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void aJoinRecordsTheInventoryTheFirstJoinIncluded() {
        new PlayerJoinEvent(player, Component.text("Alex joined")).callEvent();

        List<StoredEvent> joins = Recorded.events(Action.SNAPSHOT_JOIN);
        assertEquals(2, joins.size(), joins.toString());
        assertEquals(0, joins.getFirst().amount(), "addPlayer joined with nothing");
        assertEquals(new Payload.Snapshot("", List.of()), joins.getFirst().payload().orElseThrow());
        StoredEvent join = joins.get(1);
        assertEquals("Alex", join.actor().name());
        assertEquals("", join.subject());
        assertEquals(64 + 5 + 1 + 1, join.amount());
        assertEquals(List.of(0, 8, 38, 40), indexes(join));
        assertEquals("", snapshot(join).detail());
        assertEquals(List.of(4, 70, -9), List.of(join.x(), join.y(), join.z()));
    }

    @Test
    public void aQuitRecordsTheInventoryWithTheCursorAndForgetsThePlayer() {
        player.setItemOnCursor(new ItemStack(Material.TORCH, 3));
        player.teleport(new Location(nether, 0, 64, 0));
        assertEquals(1, TestPlugins.bean(InventorySnapshotListener.class).trackedPlayers());
        player.disconnect();

        StoredEvent quit = Recorded.only(Action.SNAPSHOT_QUIT);
        assertEquals(64 + 5 + 1 + 1 + 3, quit.amount());
        assertEquals(List.of(0, 8, 38, 40, Payload.Snapshot.CURSOR), indexes(quit));
        assertEquals("world_nether", quit.world());
        assertEquals(0, TestPlugins.bean(InventorySnapshotListener.class).trackedPlayers());
    }

    @Test
    public void aDeathRecordsTheInventoryAndTheDeathMessageOnOneLine() {
        new PlayerDeathEvent(player, DamageSource.builder(DamageType.FALL).build(), List.of(), 0,
                Component.text("Alex fell").append(Component.newline()).append(Component.text("from a high place")),
                true).callEvent();
        new PlayerDeathEvent(player, DamageSource.builder(DamageType.FALL).build(), List.of(), 0,
                Component.text("x".repeat(300)), true).callEvent();
        new PlayerDeathEvent(player, DamageSource.builder(DamageType.FALL).build(), List.of(), 0, (Component) null,
                true).callEvent();

        List<StoredEvent> deaths = Recorded.events(Action.SNAPSHOT_DEATH);
        assertEquals(3, deaths.size(), deaths.toString());
        assertEquals("Alex fell from a high place", snapshot(deaths.get(0)).detail());
        assertEquals(64 + 5 + 1 + 1, deaths.get(0).amount());
        assertEquals(List.of(0, 8, 38, 40), indexes(deaths.get(0)));
        assertEquals(InventorySnapshotListener.MAX_DEATH_MESSAGE, snapshot(deaths.get(1)).detail().length());
        assertEquals("", snapshot(deaths.get(2)).detail());
    }

    @Test
    public void eachSwitchTurnsItsSnapshotOff() {
        TestPlugins.writeConfig(plugin, "logging:\n  join-snapshots: false\n  quit-snapshots: false\n"
                + "  teleport-snapshots: false\n  death-snapshots: false\n");
        TestPlugins.reload();
        int before = snapshots().size();

        new PlayerJoinEvent(player, Component.text("Alex joined")).callEvent();
        player.teleport(new Location(nether, 0, 64, 0));
        new PlayerChangedWorldEvent(player, world).callEvent();
        new PlayerDeathEvent(player, DamageSource.builder(DamageType.FALL).build(), List.of(), 0,
                Component.text("Alex died"), true).callEvent();
        player.disconnect();

        assertEquals(before, snapshots().size());
    }

    @Test
    public void aDisabledWorldRecordsNoSnapshot() {
        TestPlugins.writeConfig(plugin, "logging:\n  disabled-worlds: [world]\n");
        TestPlugins.reload();
        int before = snapshots().size();

        new PlayerJoinEvent(player, Component.text("Alex joined")).callEvent();
        player.teleport(new Location(world, 40, 64, 40));
        new PlayerDeathEvent(player, DamageSource.builder(DamageType.FALL).build(), List.of(), 0,
                Component.text("Alex died"), true).callEvent();
        player.disconnect();

        assertEquals(before, snapshots().size());
    }

    @Test
    public void aTeleportIsRecordedWhereThePlayerLeftWithWhereTheyWent() {
        player.teleport(new Location(nether, 10.7, 64, -4.2), TeleportCause.ENDER_PEARL);

        StoredEvent teleport = Recorded.only(Action.SNAPSHOT_TELEPORT);
        assertEquals("world", teleport.world());
        assertEquals(List.of(4, 70, -9), List.of(teleport.x(), teleport.y(), teleport.z()));
        assertEquals("ender_pearl to world_nether 10 64 -5", snapshot(teleport).detail());
        assertEquals(64 + 5 + 1 + 1, teleport.amount());
        assertEquals(List.of(0, 8, 38, 40), indexes(teleport));
    }

    @Test
    public void correctionsDismountsAndLeavingABedAreNotTravel() {
        Location from = player.getLocation();
        for (TeleportCause cause : List.of(TeleportCause.UNKNOWN, TeleportCause.DISMOUNT, TeleportCause.EXIT_BED)) {
            new PlayerTeleportEvent(player, from, new Location(nether, 0, 64, 0), cause).callEvent();
        }
        PlayerTeleportEvent cancelled = new PlayerTeleportEvent(player, from, new Location(nether, 0, 64, 0),
                TeleportCause.COMMAND);
        cancelled.setCancelled(true);
        cancelled.callEvent();

        assertTrue(Recorded.events(Action.SNAPSHOT_TELEPORT).isEmpty());
    }

    @Test
    public void withinAWorldAtMostOneTeleportASecondIsRecordedAndAnotherWorldAlwaysIs() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-24T12:00:00Z"));
        InventorySnapshotListener listener = new InventorySnapshotListener(TestPlugins.bean(EventRecorder.class),
                clock);
        Location here = player.getLocation();

        listener.onTeleport(teleport(here, new Location(world, 1, 64, 1)));
        clock.advance(Duration.ofMillis(999));
        listener.onTeleport(teleport(here, new Location(world, 2, 64, 2)));
        listener.onTeleport(teleport(here, new Location(nether, 3, 64, 3)));
        listener.onTeleport(teleport(new Location(nether, 3, 64, 3), new Location(nether, 4, 64, 4)));
        clock.advance(Duration.ofMillis(1000));
        listener.onTeleport(teleport(here, new Location(world, 5, 64, 5)));

        assertEquals(List.of("command to world 1 64 1", "command to world_nether 3 64 3", "command to world 5 64 5"),
                Recorded.events(Action.SNAPSHOT_TELEPORT).stream().map(event -> snapshot(event).detail()).toList());
    }

    @Test
    public void aPortalTripIsRecordedOnceFromItsTeleport() {
        Location from = player.getLocation();
        new PlayerPortalEvent(player, from, new Location(nether, 1, 64, 1), TeleportCause.NETHER_PORTAL).callEvent();
        assertTrue(Recorded.events(Action.SNAPSHOT_TELEPORT).isEmpty());

        player.teleport(new Location(nether, 1, 64, 1), TeleportCause.NETHER_PORTAL);

        assertEquals("nether_portal to world_nether 1 64 1",
                snapshot(Recorded.only(Action.SNAPSHOT_TELEPORT)).detail());
    }

    @Test
    public void aWorldChangeAfterARecordedTeleportRecordsNothingMoreAndAnUnannouncedOneIsRecorded() {
        player.teleport(new Location(nether, 0, 64, 0));
        assertEquals(1, Recorded.events(Action.SNAPSHOT_TELEPORT).size());

        // A respawn in another world changes worlds without a teleport event.
        player.setLocation(new Location(world, 7, 65, 7));
        new PlayerChangedWorldEvent(player, nether).callEvent();

        List<StoredEvent> teleports = Recorded.events(Action.SNAPSHOT_TELEPORT);
        assertEquals(2, teleports.size(), teleports.toString());
        StoredEvent change = teleports.get(1);
        assertEquals("world_change from world_nether", snapshot(change).detail());
        assertEquals("world", change.world());
        assertEquals(List.of(7, 65, 7), List.of(change.x(), change.y(), change.z()));
    }

    private @NotNull PlayerTeleportEvent teleport(@NotNull Location from, @NotNull Location to) {
        return new PlayerTeleportEvent(player, from, to, TeleportCause.COMMAND);
    }

    private static @NotNull List<StoredEvent> snapshots() {
        return Recorded.events(Action.SNAPSHOT_JOIN, Action.SNAPSHOT_QUIT, Action.SNAPSHOT_TELEPORT,
                Action.SNAPSHOT_DEATH);
    }

    private static @NotNull Payload.Snapshot snapshot(@NotNull StoredEvent event) {
        return (Payload.Snapshot) event.payload().orElseThrow();
    }

    private static @NotNull List<Integer> indexes(@NotNull StoredEvent event) {
        return snapshot(event).slots().stream().map(Payload.Slot::index).sorted().toList();
    }

    /** A clock tests move by hand. */
    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(@NotNull Instant now) {
            this.now = now;
        }

        private void advance(@NotNull Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
