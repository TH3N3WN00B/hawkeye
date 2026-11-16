package gg.moonrise.hawkeye.listener.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.listener.Recorded;
import gg.moonrise.hawkeye.support.TestHawkeyePlugin;
import gg.moonrise.hawkeye.support.TestPlugins;
import io.papermc.paper.event.player.PlayerItemFrameChangeEvent;
import io.papermc.paper.event.player.PlayerItemFrameChangeEvent.ItemFrameChangeAction;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.ItemFrame;
import org.bukkit.event.Event;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

public class EntityContainerListenerTest {

    private ServerMock server;
    private TestHawkeyePlugin plugin;
    private World world;
    private PlayerMock alex;
    private ItemFrame frame;
    private ArmorStand stand;

    @BeforeEach
    public void setUp() {
        server = MockBukkit.mock();
        plugin = TestPlugins.load();
        world = server.addSimpleWorld("world");
        alex = server.addPlayer("Alex");
        frame = world.spawn(new Location(world, 3.5, 64.5, 3.97), ItemFrame.class);
        stand = world.spawn(new Location(world, 6.5, 64, 6.5), ArmorStand.class);
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void anItemPutIntoAFrameIsOneDepositedAtItsBlock() {
        call(new PlayerItemFrameChangeEvent(alex, frame, new ItemStack(Material.DIAMOND, 64),
                ItemFrameChangeAction.PLACE));

        StoredEvent event = Recorded.only(Action.CONTAINER_DEPOSIT);
        assertEquals("Alex", event.actor().name());
        assertEquals("minecraft:diamond", event.subject());
        assertEquals(1, event.amount());
        assertEquals(List.of(3, 64, 3), List.of(event.x(), event.y(), event.z()));
    }

    @Test
    public void anItemTakenFromAFrameIsAWithdrawalAndRotatingItIsNothing() {
        call(new PlayerItemFrameChangeEvent(alex, frame, new ItemStack(Material.MAP), ItemFrameChangeAction.ROTATE));
        call(new PlayerItemFrameChangeEvent(alex, frame, new ItemStack(Material.MAP), ItemFrameChangeAction.REMOVE));

        StoredEvent event = Recorded.only(Action.CONTAINER_WITHDRAW);
        assertEquals("minecraft:map", event.subject());
        assertEquals(1, event.amount());
        assertTrue(Recorded.events(Action.CONTAINER_DEPOSIT).isEmpty());
    }

    @Test
    public void swappingWithAStandWithdrawsItsItemAndDepositsThePlayers() {
        manipulate(new ItemStack(Material.DIAMOND_HELMET), new ItemStack(Material.IRON_HELMET));

        StoredEvent withdrawn = Recorded.only(Action.CONTAINER_WITHDRAW);
        assertEquals("minecraft:iron_helmet", withdrawn.subject());
        assertEquals(List.of(6, 64, 6), List.of(withdrawn.x(), withdrawn.y(), withdrawn.z()));
        StoredEvent deposited = Recorded.only(Action.CONTAINER_DEPOSIT);
        assertEquals("minecraft:diamond_helmet", deposited.subject());
        assertEquals("Alex", deposited.actor().name());
    }

    @Test
    public void anEmptyHandOnlyTakesAndAnEmptySlotOnlyTakesOneOfAStack() {
        manipulate(new ItemStack(Material.AIR), new ItemStack(Material.IRON_SWORD));
        manipulate(new ItemStack(Material.DIAMOND, 5), new ItemStack(Material.AIR));

        assertEquals("minecraft:iron_sword", Recorded.only(Action.CONTAINER_WITHDRAW).subject());
        StoredEvent deposited = Recorded.only(Action.CONTAINER_DEPOSIT);
        assertEquals("minecraft:diamond", deposited.subject());
        assertEquals(1, deposited.amount());
    }

    @Test
    public void aStackIsNotSwappedWithAnOccupiedSlot() {
        manipulate(new ItemStack(Material.DIAMOND, 5), new ItemStack(Material.STICK));

        assertTrue(Recorded.events(Action.CONTAINER_DEPOSIT, Action.CONTAINER_WITHDRAW).isEmpty());
    }

    @Test
    public void aDisabledCategoryRecordsNothing() {
        TestPlugins.writeConfig(plugin, "logging:\n  containers: false\n");
        TestPlugins.reload();

        call(new PlayerItemFrameChangeEvent(alex, frame, new ItemStack(Material.DIAMOND), ItemFrameChangeAction.PLACE));
        manipulate(new ItemStack(Material.DIAMOND_HELMET), new ItemStack(Material.IRON_HELMET));

        assertTrue(Recorded.events(Action.CONTAINER_DEPOSIT, Action.CONTAINER_WITHDRAW).isEmpty());
    }

    private void manipulate(ItemStack playerItem, ItemStack standItem) {
        call(new PlayerArmorStandManipulateEvent(alex, stand, playerItem, standItem, EquipmentSlot.HEAD,
                EquipmentSlot.HAND));
    }

    private void call(Event event) {
        server.getPluginManager().callEvent(event);
    }
}
