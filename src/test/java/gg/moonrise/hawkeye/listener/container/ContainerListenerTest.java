package gg.moonrise.hawkeye.listener.container;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.listener.HeldInventories;
import gg.moonrise.hawkeye.listener.Recorded;
import gg.moonrise.hawkeye.support.TestHawkeyePlugin;
import gg.moonrise.hawkeye.support.TestPlugins;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Item;
import org.bukkit.entity.Villager;
import org.bukkit.entity.boat.OakChestBoat;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

public class ContainerListenerTest {

    private ServerMock server;
    private TestHawkeyePlugin plugin;
    private World world;
    private PlayerMock player;
    private Inventory chest;

    @BeforeEach
    public void setUp() {
        server = MockBukkit.mock();
        plugin = TestPlugins.load();
        world = server.addSimpleWorld("world");
        player = server.addPlayer("Alex");
        Block block = world.getBlockAt(2, 64, -3);
        block.setType(Material.CHEST);
        chest = HeldInventories.chest((InventoryHolder) block.getState());
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void aClickRecordsTheNetChangeAtTheContainerNextTick() {
        chest.setItem(0, new ItemStack(Material.STONE, 10));
        player.openInventory(chest);
        chest.setItem(0, new ItemStack(Material.STONE, 4));
        chest.setItem(1, new ItemStack(Material.DIAMOND, 2));
        click(player);
        server.getScheduler().performOneTick();

        List<StoredEvent> events = Recorded.events(Action.CONTAINER_WITHDRAW, Action.CONTAINER_DEPOSIT);
        assertEquals(2, events.size());
        StoredEvent withdrawal = events.get(0);
        assertEquals(Action.CONTAINER_WITHDRAW, withdrawal.action());
        assertEquals("minecraft:stone", withdrawal.subject());
        assertEquals(6, withdrawal.amount());
        assertEquals("Alex", withdrawal.actor().name());
        assertEquals(2, withdrawal.x());
        assertEquals(-3, withdrawal.z());
        StoredEvent deposit = events.get(1);
        assertEquals(Action.CONTAINER_DEPOSIT, deposit.action());
        assertEquals("minecraft:diamond", deposit.subject());
        assertEquals(2, deposit.amount());
    }

    @Test
    public void aDragIsComparedLikeAClick() {
        player.openInventory(chest);
        ItemStack dragged = new ItemStack(Material.COAL, 8);
        chest.setItem(0, new ItemStack(Material.COAL, 4));
        chest.setItem(1, new ItemStack(Material.COAL, 4));
        server.getPluginManager().callEvent(new InventoryDragEvent(player.getOpenInventory(), null, dragged, false,
                Map.of(0, chest.getItem(0), 1, chest.getItem(1))));
        server.getScheduler().performOneTick();

        StoredEvent deposit = Recorded.only(Action.CONTAINER_DEPOSIT);
        assertEquals("minecraft:coal", deposit.subject());
        assertEquals(8, deposit.amount());
    }

    @Test
    public void closingComparesOnceMoreAndEndsTheSession() {
        player.openInventory(chest);
        click(player);
        chest.addItem(new ItemStack(Material.DIAMOND, 3));
        server.getScheduler().performOneTick();
        chest.addItem(new ItemStack(Material.DIAMOND, 1));
        player.closeInventory();
        server.getScheduler().performOneTick();

        List<StoredEvent> deposits = Recorded.events(Action.CONTAINER_DEPOSIT);
        assertEquals(List.of(3, 1), deposits.stream().map(StoredEvent::amount).toList());
        assertEquals(0, TestPlugins.bean(ContainerListener.class).sessions());
    }

    @Test
    public void aChangeSeenByTwoViewersIsRecordedOnce() {
        PlayerMock other = server.addPlayer("Steve");
        player.openInventory(chest);
        other.openInventory(chest);
        chest.addItem(new ItemStack(Material.EMERALD, 5));
        click(player);
        server.getScheduler().performOneTick();
        click(other);
        server.getScheduler().performOneTick();

        StoredEvent deposit = Recorded.only(Action.CONTAINER_DEPOSIT);
        assertEquals("Alex", deposit.actor().name());
        assertEquals(5, deposit.amount());
    }

    @Test
    public void inventoriesWithoutAContainerBlockAreNotTracked() {
        Inventory virtual = HeldInventories.chest(null);
        player.openInventory(virtual);
        virtual.addItem(new ItemStack(Material.DIAMOND, 3));
        player.closeInventory();

        assertTrue(Recorded.events(Action.CONTAINER_DEPOSIT).isEmpty());
        assertEquals(0, TestPlugins.bean(ContainerListener.class).sessions());
    }

    @Test
    public void quittingDropsTheSession() {
        player.openInventory(chest);
        assertEquals(1, TestPlugins.bean(ContainerListener.class).sessions());
        player.disconnect();

        assertEquals(0, TestPlugins.bean(ContainerListener.class).sessions());
    }

    @Test
    public void aDisabledCategoryStartsNoSession() {
        TestPlugins.writeConfig(plugin, "logging:\n  containers: false\n");
        TestPlugins.reload();
        player.openInventory(chest);

        assertEquals(0, TestPlugins.bean(ContainerListener.class).sessions());
    }

    @Test
    public void aHopperPullingWhileTheChestIsOpenIsNotTheViewers() {
        chest.setItem(0, new ItemStack(Material.DIAMOND, 10));
        player.openInventory(chest);
        Inventory hopper = HeldInventories.hopper(holder(2, 63, -3, Material.HOPPER));
        ItemStack pulled = new ItemStack(Material.DIAMOND, 10);
        server.getPluginManager().callEvent(new InventoryMoveItemEvent(chest, pulled, hopper, false));
        chest.removeItem(pulled.clone());
        hopper.addItem(pulled.clone());
        click(player);
        server.getScheduler().performOneTick();
        player.closeInventory();

        assertTrue(Recorded.events(Action.CONTAINER_WITHDRAW, Action.CONTAINER_DEPOSIT).isEmpty());
    }

    @Test
    public void aDepositBeforeAHopperMoveInTheSameTickIsStillTheViewers() {
        player.openInventory(chest);
        click(player);
        chest.setItem(5, new ItemStack(Material.EMERALD, 4));
        Inventory hopper = HeldInventories.hopper(holder(2, 65, -3, Material.HOPPER));
        hopper.setItem(0, new ItemStack(Material.IRON_INGOT, 1));
        ItemStack pushed = new ItemStack(Material.IRON_INGOT, 1);
        server.getPluginManager().callEvent(new InventoryMoveItemEvent(hopper, pushed, chest, true));
        hopper.removeItem(pushed.clone());
        chest.addItem(pushed.clone());
        server.getScheduler().performOneTick();
        player.closeInventory();

        StoredEvent deposit = Recorded.only(Action.CONTAINER_DEPOSIT);
        assertEquals("Alex", deposit.actor().name());
        assertEquals("minecraft:emerald", deposit.subject());
        assertEquals(4, deposit.amount());
        assertTrue(Recorded.events(Action.CONTAINER_WITHDRAW).isEmpty());
    }

    @Test
    public void aClickAfterAnAutomaticMoveIsTheViewersAgain() {
        chest.setItem(0, new ItemStack(Material.DIAMOND, 10));
        player.openInventory(chest);
        Inventory hopper = HeldInventories.hopper(holder(2, 63, -3, Material.HOPPER));
        server.getPluginManager().callEvent(new InventoryMoveItemEvent(chest, new ItemStack(Material.DIAMOND),
                hopper, false));
        chest.removeItem(new ItemStack(Material.DIAMOND));
        click(player);
        chest.removeItem(new ItemStack(Material.DIAMOND, 3));
        server.getScheduler().performOneTick();

        StoredEvent withdrawal = Recorded.only(Action.CONTAINER_WITHDRAW);
        assertEquals("Alex", withdrawal.actor().name());
        assertEquals(3, withdrawal.amount());
    }

    @Test
    public void aGroundItemPickedUpByAnOpenHopperIsNotTheViewers() {
        Inventory hopper = HeldInventories.hopper(holder(4, 64, -3, Material.HOPPER));
        player.openInventory(hopper);
        Item item = world.dropItem(new Location(world, 4, 65, -3), new ItemStack(Material.GOLD_NUGGET, 8));
        server.getPluginManager().callEvent(new InventoryPickupItemEvent(hopper, item));
        hopper.addItem(item.getItemStack().clone());
        player.closeInventory();

        assertTrue(Recorded.events(Action.CONTAINER_DEPOSIT).isEmpty());
    }

    @Test
    public void aStorageMinecartIsRecordedAtItsBlock() {
        StorageMinecart minecart = world.spawn(new Location(world, 10.7, 65.2, -4.3), StorageMinecart.class);
        Inventory inventory = HeldInventories.chest(minecart);
        player.openInventory(inventory);
        inventory.addItem(new ItemStack(Material.DIAMOND, 3));
        player.closeInventory();

        StoredEvent deposit = Recorded.only(Action.CONTAINER_DEPOSIT);
        assertEquals("Alex", deposit.actor().name());
        assertEquals(3, deposit.amount());
        assertEquals(10, deposit.x());
        assertEquals(65, deposit.y());
        assertEquals(-5, deposit.z());
    }

    @Test
    public void aChestBoatIsRecordedAtItsBlock() {
        OakChestBoat boat = world.spawn(new Location(world, -1.5, 62.4, 8.2), OakChestBoat.class);
        Inventory inventory = HeldInventories.chest(boat);
        inventory.setItem(0, new ItemStack(Material.COAL, 16));
        player.openInventory(inventory);
        inventory.setItem(0, new ItemStack(Material.COAL, 6));
        player.closeInventory();

        StoredEvent withdrawal = Recorded.only(Action.CONTAINER_WITHDRAW);
        assertEquals(10, withdrawal.amount());
        assertEquals(-2, withdrawal.x());
        assertEquals(62, withdrawal.y());
        assertEquals(8, withdrawal.z());
    }

    @Test
    public void aVillagersInventoryIsNotTracked() {
        Villager villager = world.spawn(new Location(world, 0, 64, 0), Villager.class);
        player.openInventory(HeldInventories.chest(villager));

        assertEquals(0, TestPlugins.bean(ContainerListener.class).sessions());
    }

    private InventoryHolder holder(int x, int y, int z, Material material) {
        Block block = world.getBlockAt(x, y, z);
        block.setType(material);
        return (InventoryHolder) block.getState();
    }

    private void click(PlayerMock viewer) {
        server.getPluginManager().callEvent(new InventoryClickEvent(viewer.getOpenInventory(),
                InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL));
    }
}
