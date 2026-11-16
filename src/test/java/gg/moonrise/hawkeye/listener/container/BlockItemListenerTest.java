package gg.moonrise.hawkeye.listener.container;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.listener.Recorded;
import gg.moonrise.hawkeye.support.TestHawkeyePlugin;
import gg.moonrise.hawkeye.support.TestPlugins;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.ChiseledBookshelf;
import org.bukkit.block.DecoratedPot;
import org.bukkit.block.Jukebox;
import org.bukkit.block.Shelf;
import org.bukkit.event.Event;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

public class BlockItemListenerTest {

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
    public void anItemPutIntoADecoratedPotIsADepositAtThePotNextTick() {
        Block block = block(4, Material.DECORATED_POT);
        DecoratedPot pot = (DecoratedPot) block.getState();
        rightClick(block, EquipmentSlot.HAND);
        pot.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 1));
        assertTrue(Recorded.events(Action.CONTAINER_DEPOSIT).isEmpty());
        server.getScheduler().performOneTick();

        StoredEvent deposit = Recorded.only(Action.CONTAINER_DEPOSIT);
        assertEquals("Alex", deposit.actor().name());
        assertEquals("minecraft:diamond", deposit.subject());
        assertEquals(1, deposit.amount());
        assertEquals(4, deposit.x());
        assertEquals(64, deposit.y());
        assertEquals(0, TestPlugins.bean(BlockItemListener.class).pending());
    }

    @Test
    public void aBookTakenFromAChiseledBookshelfIsAWithdrawal() {
        Block block = block(5, Material.CHISELED_BOOKSHELF);
        ChiseledBookshelf bookshelf = (ChiseledBookshelf) block.getState();
        bookshelf.getInventory().setItem(2, new ItemStack(Material.ENCHANTED_BOOK));
        rightClick(block, EquipmentSlot.HAND);
        bookshelf.getInventory().setItem(2, null);
        server.getScheduler().performOneTick();

        StoredEvent withdrawal = Recorded.only(Action.CONTAINER_WITHDRAW);
        assertEquals("minecraft:enchanted_book", withdrawal.subject());
        assertEquals(1, withdrawal.amount());
    }

    @Test
    public void aRecordPutIntoAJukeboxIsADeposit() {
        Block block = block(6, Material.JUKEBOX);
        Jukebox jukebox = (Jukebox) block.getState();
        rightClick(block, EquipmentSlot.HAND);
        jukebox.setRecord(new ItemStack(Material.MUSIC_DISC_CAT));
        server.getScheduler().performOneTick();

        assertEquals("minecraft:music_disc_cat", Recorded.only(Action.CONTAINER_DEPOSIT).subject());
    }

    @Test
    public void swappingAnItemOnAShelfIsAWithdrawalAndADeposit() {
        Block block = block(7, Material.OAK_SHELF);
        Shelf shelf = (Shelf) block.getState();
        shelf.getInventory().setItem(1, new ItemStack(Material.LANTERN));
        rightClick(block, EquipmentSlot.HAND);
        shelf.getInventory().setItem(1, new ItemStack(Material.CANDLE, 3));
        server.getScheduler().performOneTick();

        List<StoredEvent> events = Recorded.events(Action.CONTAINER_WITHDRAW, Action.CONTAINER_DEPOSIT);
        assertEquals(List.of(Action.CONTAINER_WITHDRAW, Action.CONTAINER_DEPOSIT),
                events.stream().map(StoredEvent::action).toList());
        assertEquals(List.of("minecraft:lantern", "minecraft:candle"),
                events.stream().map(StoredEvent::subject).toList());
        assertEquals(List.of(1, 3), events.stream().map(StoredEvent::amount).toList());
    }

    @Test
    public void bothHandsClickingInOneTickAreComparedOnce() {
        Block block = block(4, Material.DECORATED_POT);
        DecoratedPot pot = (DecoratedPot) block.getState();
        rightClick(block, EquipmentSlot.HAND);
        rightClick(block, EquipmentSlot.OFF_HAND);
        pot.getInventory().setItem(0, new ItemStack(Material.EMERALD, 1));
        server.getScheduler().performOneTick();

        assertEquals(1, Recorded.only(Action.CONTAINER_DEPOSIT).amount());
    }

    @Test
    public void aDeniedUseAClickThatChangesNothingAndOtherBlocksAreNotRecorded() {
        Block pot = block(4, Material.DECORATED_POT);
        PlayerInteractEvent denied = interact(pot, EquipmentSlot.HAND);
        denied.setUseInteractedBlock(Event.Result.DENY);
        server.getPluginManager().callEvent(denied);
        ((DecoratedPot) pot.getState()).getInventory().setItem(0, new ItemStack(Material.EMERALD, 1));
        server.getScheduler().performOneTick();
        Block bookshelf = block(5, Material.CHISELED_BOOKSHELF);
        rightClick(bookshelf, EquipmentSlot.HAND);
        Block chest = block(6, Material.CHEST);
        rightClick(chest, EquipmentSlot.HAND);
        server.getScheduler().performOneTick();

        assertTrue(Recorded.events(Action.CONTAINER_DEPOSIT, Action.CONTAINER_WITHDRAW).isEmpty());
        assertEquals(0, TestPlugins.bean(BlockItemListener.class).pending());
    }

    @Test
    public void aDisabledCategorySchedulesNothing() {
        TestPlugins.writeConfig(plugin, "logging:\n  containers: false\n");
        TestPlugins.reload();
        rightClick(block(4, Material.DECORATED_POT), EquipmentSlot.HAND);

        assertEquals(0, TestPlugins.bean(BlockItemListener.class).pending());
    }

    private Block block(int x, Material material) {
        Block block = world.getBlockAt(x, 64, 0);
        block.setType(material);
        return block;
    }

    private void rightClick(Block block, EquipmentSlot hand) {
        server.getPluginManager().callEvent(interact(block, hand));
    }

    private PlayerInteractEvent interact(Block block, EquipmentSlot hand) {
        return new PlayerInteractEvent(player, org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK, null, block,
                BlockFace.NORTH, hand);
    }
}
