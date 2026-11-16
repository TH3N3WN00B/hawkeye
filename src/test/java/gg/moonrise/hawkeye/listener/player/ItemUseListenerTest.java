package gg.moonrise.hawkeye.listener.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.listener.Recorded;
import gg.moonrise.hawkeye.support.TestHawkeyePlugin;
import gg.moonrise.hawkeye.support.TestPlugins;
import io.papermc.paper.event.inventory.ItemCraftedEvent;
import io.papermc.paper.event.player.PlayerTradeEvent;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Villager;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.FireworkMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.entity.SnowballMock;
import org.mockbukkit.mockbukkit.entity.TridentMock;

public class ItemUseListenerTest {

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
        player.teleport(new Location(world, 8, 72, -4));
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void aCraftIsRecordedWithTheCraftedCountWhereThePlayerStands() {
        server.getPluginManager().callEvent(new ItemCraftedEvent(player, new ItemStack(Material.OAK_PLANKS, 4)));

        StoredEvent event = Recorded.only(Action.ITEM_CRAFT);
        assertEquals("Alex", event.actor().name());
        assertEquals("minecraft:oak_planks", event.subject());
        assertEquals(4, event.amount());
        assertEquals(8, event.x());
        assertEquals(72, event.y());
        assertEquals(-4, event.z());
    }

    @Test
    public void aTradeRecordsTheBoughtResult() {
        Villager villager = world.spawn(new Location(world, 9, 72, -4), Villager.class);
        MerchantRecipe recipe = new MerchantRecipe(new ItemStack(Material.BREAD, 6), 12);
        recipe.addIngredient(new ItemStack(Material.EMERALD));
        server.getPluginManager().callEvent(new PlayerTradeEvent(player, villager, recipe, true, true));

        StoredEvent event = Recorded.only(Action.ITEM_TRADE);
        assertEquals("minecraft:bread", event.subject());
        assertEquals(6, event.amount());
        assertEquals(8, event.x());
    }

    @Test
    public void aWornOutToolIsOneBrokenItem() {
        server.getPluginManager().callEvent(new PlayerItemBreakEvent(player, new ItemStack(Material.DIAMOND_PICKAXE)));

        StoredEvent event = Recorded.only(Action.ITEM_BREAK);
        assertEquals("minecraft:diamond_pickaxe", event.subject());
        assertEquals(1, event.amount());
    }

    @Test
    public void aThrownSnowballIsRecordedForItsThrower() {
        SnowballMock snowball = new SnowballMock(server, UUID.randomUUID());
        launch(snowball);

        StoredEvent event = Recorded.only(Action.ITEM_THROW);
        assertEquals("Alex", event.actor().name());
        assertEquals("minecraft:snowball", event.subject());
        assertEquals(1, event.amount());
        assertEquals(8, event.x());
    }

    @Test
    public void aThrownTridentIsItsItem() {
        launch(new TridentWithItem(server, new ItemStack(Material.TRIDENT)));

        assertEquals("minecraft:trident", Recorded.only(Action.ITEM_THROW).subject());
    }

    @Test
    public void fireworksAndProjectilesWithoutAPlayerAreNotRecorded() {
        launch(new FireworkMock(server, UUID.randomUUID()));
        SnowballMock mobSnowball = new SnowballMock(server, UUID.randomUUID());
        mobSnowball.setShooter(world.spawn(new Location(world, 0, 64, 0), Zombie.class));
        server.getPluginManager().callEvent(new ProjectileLaunchEvent(mobSnowball));

        assertTrue(Recorded.events(Action.ITEM_THROW).isEmpty());
    }

    @Test
    public void aDisabledCategoryRecordsNoThrowsOrBreaks() {
        TestPlugins.writeConfig(plugin, "logging:\n  item-use: false\n");
        TestPlugins.reload();
        launch(new SnowballMock(server, UUID.randomUUID()));
        server.getPluginManager().callEvent(new PlayerItemBreakEvent(player, new ItemStack(Material.IRON_AXE)));

        assertTrue(Recorded.events(Action.ITEM_THROW, Action.ITEM_BREAK).isEmpty());
    }

    private void launch(Projectile projectile) {
        projectile.setShooter(player);
        server.getPluginManager().callEvent(new ProjectileLaunchEvent(projectile));
    }

    /** MockBukkit 4.120 does not implement {@code AbstractArrow#getItemStack()}. */
    private static final class TridentWithItem extends TridentMock {

        private final ItemStack item;

        private TridentWithItem(ServerMock server, ItemStack item) {
            super(server, UUID.randomUUID());
            this.item = item;
        }

        @Override
        public @NotNull ItemStack getItemStack() {
            return item.clone();
        }
    }
}
