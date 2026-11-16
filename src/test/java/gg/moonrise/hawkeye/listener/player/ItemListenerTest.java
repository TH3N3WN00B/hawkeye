package gg.moonrise.hawkeye.listener.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.listener.Recorded;
import gg.moonrise.hawkeye.support.TestPlugins;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

public class ItemListenerTest {

    private ServerMock server;
    private World world;
    private PlayerMock player;

    @BeforeEach
    public void setUp() {
        server = MockBukkit.mock();
        TestPlugins.load();
        world = server.addSimpleWorld("world");
        player = server.addPlayer("Alex");
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void aDropIsRecordedAtTheItem() {
        Item drop = world.dropItem(new Location(world, 3, 70, 3), new ItemStack(Material.COBBLESTONE, 12));
        server.getPluginManager().callEvent(new PlayerDropItemEvent(player, drop));

        StoredEvent event = Recorded.only(Action.ITEM_DROP);
        assertEquals("Alex", event.actor().name());
        assertEquals("minecraft:cobblestone", event.subject());
        assertEquals(12, event.amount());
        assertEquals(70, event.y());
    }

    @Test
    public void aPickupCountsOnlyWhatWasTaken() {
        Item item = world.dropItem(new Location(world, 0, 64, 0), new ItemStack(Material.ARROW, 20));
        server.getPluginManager().callEvent(new EntityPickupItemEvent(player, item, 5));

        StoredEvent event = Recorded.only(Action.ITEM_PICKUP);
        assertEquals("minecraft:arrow", event.subject());
        assertEquals(15, event.amount());
    }

    @Test
    public void mobPickupsAreNotRecorded() {
        Location location = new Location(world, 0, 64, 0);
        Item item = world.dropItem(location, new ItemStack(Material.ARROW, 20));
        server.getPluginManager().callEvent(new EntityPickupItemEvent(world.spawn(location, Zombie.class), item, 0));

        assertTrue(Recorded.events(Action.ITEM_PICKUP).isEmpty());
    }
}
