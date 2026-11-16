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
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Item;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.HopperMinecartMock;

public class HopperListenerTest {

    private ServerMock server;
    private TestHawkeyePlugin plugin;
    private World world;
    private Inventory chest;

    @BeforeEach
    public void setUp() {
        server = MockBukkit.mock();
        plugin = TestPlugins.load();
        world = server.addSimpleWorld("world");
        chest = HeldInventories.chest(holder(0, Material.CHEST));
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void transfersAreOffByDefault() {
        move(HeldInventories.hopper(holder(1, Material.HOPPER)), chest);

        assertTrue(Recorded.events(Action.CONTAINER_WITHDRAW, Action.CONTAINER_DEPOSIT).isEmpty());
    }

    @Test
    public void aHopperTransferIsAWithdrawalAndADeposit() {
        enable();
        move(HeldInventories.hopper(holder(1, Material.HOPPER)), chest);

        List<StoredEvent> events = Recorded.events(Action.CONTAINER_WITHDRAW, Action.CONTAINER_DEPOSIT);
        assertEquals(2, events.size());
        assertEquals(Action.CONTAINER_WITHDRAW, events.get(0).action());
        assertEquals(1, events.get(0).x());
        assertEquals(Action.CONTAINER_DEPOSIT, events.get(1).action());
        assertEquals(0, events.get(1).x());
        assertTrue(events.stream().allMatch(event -> event.actor().name().equals("#hopper")
                && event.subject().equals("minecraft:iron_ingot") && event.amount() == 1));
    }

    @Test
    public void aDropperIsTheDispenserAndAnEndWithoutABlockIsSkipped() {
        enable();
        move(HeldInventories.dropper(holder(1, Material.DROPPER)), HeldInventories.hopper(server.addPlayer("Alex")));

        StoredEvent withdrawal = Recorded.only(Action.CONTAINER_WITHDRAW);
        assertEquals("#dispenser", withdrawal.actor().name());
        assertTrue(Recorded.events(Action.CONTAINER_DEPOSIT).isEmpty());
    }

    @Test
    public void groundPickupsAreOffByDefault() {
        pickup(HeldInventories.hopper(holder(1, Material.HOPPER)), 8);

        assertTrue(Recorded.events(Action.CONTAINER_DEPOSIT).isEmpty());
    }

    @Test
    public void aHopperPickingUpAGroundItemIsADepositAtTheHopper() {
        enable();
        pickup(HeldInventories.hopper(holder(3, Material.HOPPER)), 8);

        StoredEvent deposit = Recorded.only(Action.CONTAINER_DEPOSIT);
        assertEquals("#hopper", deposit.actor().name());
        assertEquals("minecraft:gold_nugget", deposit.subject());
        assertEquals(8, deposit.amount());
        assertEquals(3, deposit.x());
        assertEquals(64, deposit.y());
    }

    @Test
    public void aHopperMinecartPickupIsRecordedAtTheMinecartsBlock() {
        enable();
        HopperMinecartMock minecart = new HopperMinecartMock(server, UUID.randomUUID());
        minecart.setLocation(new Location(world, 10.7, 65.2, -4.3));
        pickup(HeldInventories.hopper(minecart), 2);

        StoredEvent deposit = Recorded.only(Action.CONTAINER_DEPOSIT);
        assertEquals(10, deposit.x());
        assertEquals(65, deposit.y());
        assertEquals(-5, deposit.z());
    }

    @Test
    public void onlyWhatFitsIsRecordedAndNothingWhenNothingFits() {
        enable();
        Inventory nearlyFull = HeldInventories.hopper(holder(1, Material.HOPPER));
        for (int slot = 0; slot < nearlyFull.getSize(); slot++) {
            nearlyFull.setItem(slot, new ItemStack(Material.STONE, 64));
        }
        nearlyFull.setItem(2, new ItemStack(Material.GOLD_NUGGET, 61));
        pickup(nearlyFull, 8);
        Inventory full = HeldInventories.hopper(holder(2, Material.HOPPER));
        for (int slot = 0; slot < full.getSize(); slot++) {
            full.setItem(slot, new ItemStack(Material.STONE, 64));
        }
        pickup(full, 8);

        StoredEvent deposit = Recorded.only(Action.CONTAINER_DEPOSIT);
        assertEquals(1, deposit.x());
        assertEquals(3, deposit.amount());
    }

    private void enable() {
        TestPlugins.writeConfig(plugin, "logging:\n  hoppers: true\n");
        TestPlugins.reload();
    }

    private InventoryHolder holder(int x, Material material) {
        Block block = world.getBlockAt(x, 64, 0);
        block.setType(material);
        return (InventoryHolder) block.getState();
    }

    private void move(Inventory source, Inventory destination) {
        server.getPluginManager().callEvent(new InventoryMoveItemEvent(source, new ItemStack(Material.IRON_INGOT),
                destination, true));
    }

    private void pickup(Inventory hopper, int amount) {
        Item item = world.dropItem(new Location(world, 0, 66, 0), new ItemStack(Material.GOLD_NUGGET, amount));
        server.getPluginManager().callEvent(new InventoryPickupItemEvent(hopper, item));
    }
}
