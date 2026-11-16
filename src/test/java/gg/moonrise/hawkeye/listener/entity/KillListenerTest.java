package gg.moonrise.hawkeye.listener.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.listener.Recorded;
import gg.moonrise.hawkeye.support.TestHawkeyePlugin;
import gg.moonrise.hawkeye.support.TestPlugins;
import java.util.ArrayList;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

public class KillListenerTest {

    private ServerMock server;
    private TestHawkeyePlugin plugin;
    private World world;
    private PlayerMock player;

    @BeforeEach
    public void setUp() {
        server = MockBukkit.mock();
        plugin = TestPlugins.load();
        world = server.addSimpleWorld("world");
        player = server.addPlayer("Alex");
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void aMobKilledByAPlayerIsRecordedAtTheMob() {
        Zombie zombie = world.spawn(new Location(world, 5, 64, 5), Zombie.class);
        zombie.setKiller(player);
        server.getPluginManager().callEvent(new EntityDeathEvent(zombie, source(), new ArrayList<>()));

        StoredEvent event = Recorded.only(Action.ENTITY_KILL);
        assertEquals("Alex", event.actor().name());
        assertEquals("minecraft:zombie", event.subject());
        assertEquals(5, event.x());
    }

    @Test
    public void aPlayerDeathArrivesThroughTheSameHandler() {
        server.getPluginManager().callEvent(new PlayerDeathEvent(player, source(), new ArrayList<>(), 0,
                Component.empty(), true));

        StoredEvent event = Recorded.only(Action.ENTITY_KILL);
        assertEquals("#fall", event.actor().name());
        assertEquals("minecraft:player", event.subject());
    }

    @Test
    public void aDisabledCategoryRecordsNothing() {
        TestPlugins.writeConfig(plugin, "logging:\n  kills: false\n");
        TestPlugins.reload();
        Zombie zombie = world.spawn(new Location(world, 0, 64, 0), Zombie.class);
        server.getPluginManager().callEvent(new EntityDeathEvent(zombie, source(), new ArrayList<>()));

        assertTrue(Recorded.events(Action.ENTITY_KILL).isEmpty());
    }

    private static DamageSource source() {
        return DamageSource.builder(DamageType.FALL).build();
    }
}
