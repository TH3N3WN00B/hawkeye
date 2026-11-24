package gg.moonrise.hawkeye.listener.container;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.entity.payload.Payload;
import gg.moonrise.hawkeye.listener.HeldInventories;
import gg.moonrise.hawkeye.listener.Recorded;
import gg.moonrise.hawkeye.support.TestHawkeyePlugin;
import gg.moonrise.hawkeye.support.TestPlugins;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * MockBukkit's own ender chest ({@code getEnderChest()}) and {@code createInventory} inventories do not
 * answer {@code getHolder(boolean)}, so these tests open {@link HeldInventories} of the same type and
 * holder, as Paper builds them (an ender chest's holder is its owner).
 */
public class VirtualContainerListenerTest {

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
        player.setLocation(new Location(world, 4.5, 70, -8.5));
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void aDepositIntoTheOwnEnderChestIsRecordedWhereThePlayerStands() {
        Inventory ender = HeldInventories.typed(player, InventoryType.ENDER_CHEST);
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 10));
        player.openInventory(ender);
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 4));
        ender.setItem(3, new ItemStack(Material.DIAMOND, 6));
        click(player);
        server.getScheduler().performOneTick();

        StoredEvent deposit = Recorded.only(Action.VIRTUAL_DEPOSIT);
        assertEquals("minecraft:diamond", deposit.subject());
        assertEquals(6, deposit.amount());
        assertEquals("Alex", deposit.actor().name());
        assertEquals(List.of(4, 70, -9), List.of(deposit.x(), deposit.y(), deposit.z()));
        assertEquals("ender_chest", virtual(deposit).container());
        assertTrue(Recorded.events(Action.VIRTUAL_WITHDRAW, Action.CONTAINER_DEPOSIT, Action.CONTAINER_WITHDRAW)
                .isEmpty());
    }

    @Test
    public void aWithdrawalIsRecordedAtTheClose() {
        Inventory ender = HeldInventories.typed(player, InventoryType.ENDER_CHEST);
        ender.setItem(0, new ItemStack(Material.EMERALD, 12));
        player.openInventory(ender);
        ender.setItem(0, new ItemStack(Material.EMERALD, 2));
        player.getInventory().setItem(5, new ItemStack(Material.EMERALD, 10));
        player.closeInventory();

        StoredEvent withdrawal = Recorded.only(Action.VIRTUAL_WITHDRAW);
        assertEquals("minecraft:emerald", withdrawal.subject());
        assertEquals(10, withdrawal.amount());
        assertEquals(0, TestPlugins.bean(VirtualContainerListener.class).sessions());
    }

    @Test
    public void anItemWithComponentsCarriesItsBytes() {
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        sword.editMeta(meta -> meta.displayName(Component.text("Excalibur")));
        Inventory ender = HeldInventories.typed(player, InventoryType.ENDER_CHEST);
        player.getInventory().setItem(0, sword.clone());
        player.openInventory(ender);
        player.getInventory().setItem(0, null);
        ender.setItem(0, sword.clone());
        player.closeInventory();

        StoredEvent deposit = Recorded.only(Action.VIRTUAL_DEPOSIT);
        assertEquals("minecraft:diamond_sword", deposit.subject());
        assertTrue(virtual(deposit).item().length > 0);
    }

    @Test
    public void aPluginInventoryIsLabelledByItsTitle() {
        Inventory vault = HeldInventories.chest(null, Component.text("Vault #1"));
        player.getInventory().setItem(0, new ItemStack(Material.GOLD_INGOT, 32));
        player.openInventory(vault);
        player.getInventory().setItem(0, null);
        vault.setItem(10, new ItemStack(Material.GOLD_INGOT, 32));
        player.closeInventory();

        StoredEvent deposit = Recorded.only(Action.VIRTUAL_DEPOSIT);
        assertEquals(32, deposit.amount());
        assertEquals("Vault #1", virtual(deposit).container());
    }

    @Test
    public void anotherPlayersInventoryIsLabelledWithTheirName() {
        PlayerMock steve = server.addPlayer("Steve");
        Inventory target = HeldInventories.typed(steve, InventoryType.PLAYER);
        target.setItem(0, new ItemStack(Material.TNT, 8));
        player.openInventory(target);
        target.setItem(0, null);
        player.getInventory().setItem(0, new ItemStack(Material.TNT, 8));
        player.closeInventory();

        StoredEvent withdrawal = Recorded.only(Action.VIRTUAL_WITHDRAW);
        assertEquals(8, withdrawal.amount());
        assertEquals("Alex", withdrawal.actor().name());
        assertEquals("inventory (Steve)", virtual(withdrawal).container());
    }

    @Test
    public void aMenuChangingItsOwnContentsRecordsNothing() {
        Inventory menu = HeldInventories.chest(null, Component.text("Shop - page 1"));
        menu.setItem(0, new ItemStack(Material.DIAMOND));
        menu.setItem(1, new ItemStack(Material.EMERALD));
        player.openInventory(menu);
        menu.clear();
        menu.setItem(0, new ItemStack(Material.NETHERITE_INGOT));
        menu.setItem(8, new ItemStack(Material.ARROW));
        click(player);
        server.getScheduler().performOneTick();
        menu.setItem(4, new ItemStack(Material.CLOCK));
        player.closeInventory();

        assertTrue(Recorded.events(Action.VIRTUAL_DEPOSIT, Action.VIRTUAL_WITHDRAW).isEmpty());
    }

    @Test
    public void aShopGivingAnItemRecordsNothing() {
        Inventory menu = HeldInventories.chest(new PluginHolder(), Component.text("Shop"));
        menu.setItem(0, new ItemStack(Material.DIAMOND_SWORD));
        player.openInventory(menu);
        player.getInventory().addItem(new ItemStack(Material.DIAMOND_SWORD));
        click(player);
        server.getScheduler().performOneTick();
        player.closeInventory();

        assertTrue(Recorded.events(Action.VIRTUAL_DEPOSIT, Action.VIRTUAL_WITHDRAW).isEmpty());
    }

    @Test
    public void theDropKeyOnASlotIsAWithdrawal() {
        Inventory vault = HeldInventories.chest(null, Component.text("Vault"));
        vault.setItem(0, new ItemStack(Material.EMERALD, 5));
        player.openInventory(vault);
        vault.setItem(0, null);
        click(player);
        drop(new ItemStack(Material.EMERALD, 5));
        server.getScheduler().performOneTick();
        player.closeInventory();

        StoredEvent withdrawal = Recorded.only(Action.VIRTUAL_WITHDRAW);
        assertEquals("minecraft:emerald", withdrawal.subject());
        assertEquals(5, withdrawal.amount());
        assertTrue(Recorded.events(Action.VIRTUAL_DEPOSIT).isEmpty());
    }

    @Test
    public void takingOnTheCursorThenDroppingIsOneWithdrawal() {
        Inventory vault = HeldInventories.chest(null, Component.text("Vault"));
        vault.setItem(0, new ItemStack(Material.EMERALD, 5));
        player.openInventory(vault);
        vault.setItem(0, null);
        player.setItemOnCursor(new ItemStack(Material.EMERALD, 5));
        click(player);
        server.getScheduler().performOneTick();
        player.setItemOnCursor(null);
        click(player);
        drop(new ItemStack(Material.EMERALD, 5));
        server.getScheduler().performOneTick();
        player.closeInventory();

        assertEquals(5, Recorded.only(Action.VIRTUAL_WITHDRAW).amount());
        assertTrue(Recorded.events(Action.VIRTUAL_DEPOSIT).isEmpty());
    }

    @Test
    public void droppingFromTheOwnInventoryRecordsNothing() {
        Inventory vault = HeldInventories.chest(null, Component.text("Vault"));
        player.getInventory().setItem(0, new ItemStack(Material.COBBLESTONE, 64));
        player.openInventory(vault);
        player.getInventory().setItem(0, null);
        click(player);
        drop(new ItemStack(Material.COBBLESTONE, 64));
        server.getScheduler().performOneTick();
        player.closeInventory();

        assertTrue(Recorded.events(Action.VIRTUAL_DEPOSIT, Action.VIRTUAL_WITHDRAW).isEmpty());
    }

    @Test
    public void twoViewersOfASharedInventoryAreEachCreditedWithTheirOwnMove() {
        PlayerMock steve = server.addPlayer("Steve");
        steve.setLocation(new Location(world, -20.5, 64, 3.5));
        Inventory vault = HeldInventories.chest(new PluginHolder(), Component.text("Team vault"));
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 5));
        player.openInventory(vault);
        steve.openInventory(vault);

        player.getInventory().setItem(0, null);
        vault.setItem(0, new ItemStack(Material.DIAMOND, 5));
        click(player);
        server.getScheduler().performOneTick();
        vault.setItem(0, new ItemStack(Material.DIAMOND, 2));
        steve.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 3));
        click(steve);
        server.getScheduler().performOneTick();
        player.closeInventory();
        steve.closeInventory();

        StoredEvent deposit = Recorded.only(Action.VIRTUAL_DEPOSIT);
        assertEquals("Alex", deposit.actor().name());
        assertEquals(5, deposit.amount());
        StoredEvent withdrawal = Recorded.only(Action.VIRTUAL_WITHDRAW);
        assertEquals("Steve", withdrawal.actor().name());
        assertEquals(3, withdrawal.amount());
        assertEquals(-21, withdrawal.x());
        assertEquals("Team vault", virtual(withdrawal).container());
    }

    @Test
    public void twoViewersDepositingTheSameItemAreEachCredited() {
        PlayerMock steve = server.addPlayer("Steve");
        Inventory vault = HeldInventories.chest(null, Component.text("Team vault"));
        player.getInventory().setItem(0, new ItemStack(Material.IRON_INGOT, 4));
        steve.getInventory().setItem(0, new ItemStack(Material.IRON_INGOT, 6));
        player.openInventory(vault);
        steve.openInventory(vault);

        player.getInventory().setItem(0, null);
        vault.setItem(0, new ItemStack(Material.IRON_INGOT, 4));
        click(player);
        server.getScheduler().performOneTick();
        steve.getInventory().setItem(0, null);
        vault.setItem(0, new ItemStack(Material.IRON_INGOT, 10));
        click(steve);
        server.getScheduler().performOneTick();

        List<StoredEvent> deposits = Recorded.events(Action.VIRTUAL_DEPOSIT);
        assertEquals(List.of("Alex:4", "Steve:6"), deposits.stream()
                .map(event -> event.actor().name() + ":" + event.amount()).sorted().toList());
    }

    @Test
    public void aDisabledCategoryStartsNoSession() {
        TestPlugins.writeConfig(plugin, "logging:\n  virtual-containers: false\n");
        TestPlugins.reload();
        player.openInventory(HeldInventories.typed(player, InventoryType.ENDER_CHEST));

        assertEquals(0, TestPlugins.bean(VirtualContainerListener.class).sessions());
    }

    @Test
    public void aDisabledWorldRecordsNothing() {
        TestPlugins.writeConfig(plugin, "logging:\n  disabled-worlds:\n  - world\n");
        TestPlugins.reload();
        Inventory ender = HeldInventories.typed(player, InventoryType.ENDER_CHEST);
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 2));
        player.openInventory(ender);
        player.getInventory().setItem(0, null);
        ender.setItem(0, new ItemStack(Material.DIAMOND, 2));
        player.closeInventory();

        assertEquals(0, TestPlugins.bean(VirtualContainerListener.class).sessions());
        assertTrue(Recorded.events(Action.VIRTUAL_DEPOSIT).isEmpty());
    }

    @Test
    public void aChestBlockStaysWithTheContainerListener() {
        Block block = world.getBlockAt(2, 64, -3);
        block.setType(Material.CHEST);
        Inventory chest = HeldInventories.chest((InventoryHolder) block.getState());
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 3));
        player.openInventory(chest);
        assertEquals(0, TestPlugins.bean(VirtualContainerListener.class).sessions());
        assertEquals(1, TestPlugins.bean(ContainerListener.class).sessions());
        player.getInventory().setItem(0, null);
        chest.addItem(new ItemStack(Material.DIAMOND, 3));
        player.closeInventory();

        assertEquals(3, Recorded.only(Action.CONTAINER_DEPOSIT).amount());
        assertTrue(Recorded.events(Action.VIRTUAL_DEPOSIT, Action.VIRTUAL_WITHDRAW).isEmpty());
    }

    @Test
    public void anEnderChestIsNotAContainerListenerSession() {
        player.openInventory(HeldInventories.typed(player, InventoryType.ENDER_CHEST));

        assertEquals(1, TestPlugins.bean(VirtualContainerListener.class).sessions());
        assertEquals(0, TestPlugins.bean(ContainerListener.class).sessions());
    }

    @Test
    public void quittingDropsTheSession() {
        player.openInventory(HeldInventories.typed(player, InventoryType.ENDER_CHEST));
        assertEquals(1, TestPlugins.bean(VirtualContainerListener.class).sessions());
        player.disconnect();

        assertEquals(0, TestPlugins.bean(VirtualContainerListener.class).sessions());
    }

    @Test
    public void aWorkbenchIsNotTracked() {
        player.openInventory(HeldInventories.typed(null, InventoryType.WORKBENCH));

        assertEquals(0, TestPlugins.bean(VirtualContainerListener.class).sessions());
    }

    private void drop(@NotNull ItemStack item) {
        server.getPluginManager().callEvent(new PlayerDropItemEvent(player,
                world.dropItem(player.getLocation(), item)));
    }

    private void click(@NotNull PlayerMock viewer) {
        server.getPluginManager().callEvent(new InventoryClickEvent(viewer.getOpenInventory(),
                InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL));
    }

    private static Payload.VirtualItem virtual(@NotNull StoredEvent event) {
        return (Payload.VirtualItem) event.payload().orElseThrow();
    }

    /** A plugin's own holder. */
    private static final class PluginHolder implements InventoryHolder {

        @Override
        public @NotNull Inventory getInventory() {
            throw new UnsupportedOperationException();
        }
    }
}
