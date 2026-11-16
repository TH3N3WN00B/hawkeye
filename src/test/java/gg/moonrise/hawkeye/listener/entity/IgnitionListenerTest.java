package gg.moonrise.hawkeye.listener.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.core.service.capture.Actors;
import gg.moonrise.hawkeye.support.TestHawkeyePlugin;
import gg.moonrise.hawkeye.support.TestPlugins;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Creeper;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

public class IgnitionListenerTest {

    private ServerMock server;
    private TestHawkeyePlugin plugin;
    private PlayerMock alex;
    private Creeper creeper;

    @BeforeEach
    public void setUp() {
        server = MockBukkit.mock();
        plugin = TestPlugins.load();
        World world = server.addSimpleWorld("world");
        alex = server.addPlayer("Alex");
        creeper = world.spawn(new Location(world, 3.5, 64, 3.5), Creeper.class);
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void aCreeperLitWithFlintAndSteelCountsForThePlayer() {
        ignite(Material.FLINT_AND_STEEL, EquipmentSlot.HAND);

        assertEquals("Alex", Actors.of(creeper).name());
    }

    @Test
    public void aFireChargeInTheOffHandCountsToo() {
        ignite(Material.FIRE_CHARGE, EquipmentSlot.OFF_HAND);

        assertEquals(Actors.of(alex), Actors.taggedCause(creeper).orElseThrow());
    }

    @Test
    public void anyOtherItemLeavesTheCreeperItsOwnCause() {
        ignite(Material.STICK, EquipmentSlot.HAND);

        assertEquals("#creeper", Actors.of(creeper).name());
    }

    @Test
    public void withExplosionsOffNothingIsTagged() {
        TestPlugins.writeConfig(plugin, "logging:\n  explosions: false\n");
        TestPlugins.reload();

        ignite(Material.FLINT_AND_STEEL, EquipmentSlot.HAND);

        assertTrue(Actors.taggedCause(creeper).isEmpty());
    }

    private void ignite(Material item, EquipmentSlot hand) {
        alex.getInventory().setItem(hand, new ItemStack(item));
        server.getPluginManager().callEvent(new PlayerInteractEntityEvent(alex, creeper, hand));
    }
}
