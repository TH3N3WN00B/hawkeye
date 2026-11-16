package gg.moonrise.hawkeye.listener.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.listener.Recorded;
import gg.moonrise.hawkeye.support.TestPlugins;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Item;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerPickupArrowEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.ArrowMock;
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

    @Test
    public void anArrowPickedUpIsAPickupAtTheArrow() {
        ArrowWithItem arrow = new ArrowWithItem(server, new ItemStack(Material.SPECTRAL_ARROW));
        arrow.setLocation(new Location(world, 4, 66, -9));
        Item item = world.dropItem(arrow.getLocation(), new ItemStack(Material.SPECTRAL_ARROW));
        server.getPluginManager().callEvent(new PlayerPickupArrowEvent(player, item, arrow));

        StoredEvent event = Recorded.only(Action.ITEM_PICKUP);
        assertEquals("Alex", event.actor().name());
        assertEquals("minecraft:spectral_arrow", event.subject());
        assertEquals(1, event.amount());
        assertEquals(4, event.x());
        assertEquals(-9, event.z());
    }

    @Test
    public void deathDropsAreOneDropPerStackWhereThePlayerDied() {
        player.teleport(new Location(world, 12, 70, 5));
        List<ItemStack> drops = new ArrayList<>(List.of(new ItemStack(Material.DIAMOND, 3),
                new ItemStack(Material.IRON_SWORD)));
        server.getPluginManager().callEvent(death(drops));

        List<StoredEvent> events = Recorded.events(Action.ITEM_DROP);
        assertEquals(List.of("minecraft:diamond", "minecraft:iron_sword"),
                events.stream().map(StoredEvent::subject).toList());
        assertEquals(List.of(3, 1), events.stream().map(StoredEvent::amount).toList());
        assertTrue(events.stream().allMatch(event -> event.actor().name().equals("Alex") && event.x() == 12
                && event.y() == 70 && event.z() == 5));
    }

    @Test
    public void aDeathThatKeepsTheInventoryDropsNothing() {
        PlayerDeathEvent death = death(new ArrayList<>(List.of(new ItemStack(Material.DIAMOND, 3))));
        death.setKeepInventory(true);
        server.getPluginManager().callEvent(death);

        assertTrue(Recorded.events(Action.ITEM_DROP).isEmpty());
    }

    private PlayerDeathEvent death(List<ItemStack> drops) {
        return new PlayerDeathEvent(player, DamageSource.builder(DamageType.FALL).build(), drops, 0,
                Component.empty(), true);
    }

    /** MockBukkit 4.120 does not implement {@code AbstractArrow#getItemStack()}. */
    private static final class ArrowWithItem extends ArrowMock {

        private final ItemStack item;

        private ArrowWithItem(ServerMock server, ItemStack item) {
            super(server, UUID.randomUUID());
            this.item = item;
        }

        @Override
        public @NotNull ItemStack getItemStack() {
            return item.clone();
        }
    }
}
