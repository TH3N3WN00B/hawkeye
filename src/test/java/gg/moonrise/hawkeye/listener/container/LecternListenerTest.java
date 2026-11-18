package gg.moonrise.hawkeye.listener.container;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.listener.Recorded;
import gg.moonrise.hawkeye.support.TestPlugins;
import io.papermc.paper.event.player.PlayerInsertLecternBookEvent;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Lectern;
import org.bukkit.event.player.PlayerTakeLecternBookEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.LecternInventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

public class LecternListenerTest {

    private ServerMock server;
    private PlayerMock player;
    private Block block;

    @BeforeEach
    public void setUp() {
        server = MockBukkit.mock();
        TestPlugins.load();
        World world = server.addSimpleWorld("world");
        player = server.addPlayer("Alex");
        block = world.getBlockAt(-2, 70, 9);
        block.setType(Material.LECTERN);
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void aBookPutOnALecternIsADepositAtTheLectern() {
        server.getPluginManager().callEvent(new PlayerInsertLecternBookEvent(player, block,
                new ItemStack(Material.WRITTEN_BOOK)));

        StoredEvent deposit = Recorded.only(Action.CONTAINER_DEPOSIT);
        assertEquals("Alex", deposit.actor().name());
        assertEquals("minecraft:written_book", deposit.subject());
        assertEquals(1, deposit.amount());
        assertEquals(-2, deposit.x());
        assertEquals(70, deposit.y());
        assertEquals(9, deposit.z());
    }

    @Test
    public void aBookTakenFromALecternIsAWithdrawal() {
        Lectern lectern = (Lectern) block.getState();
        lectern.getInventory().setItem(0, new ItemStack(Material.WRITABLE_BOOK));
        server.getPluginManager().callEvent(new PlayerTakeLecternBookEvent(player, (LecternInventory) lectern.getInventory(), lectern));

        StoredEvent withdrawal = Recorded.only(Action.CONTAINER_WITHDRAW);
        assertEquals("minecraft:writable_book", withdrawal.subject());
        assertEquals(1, withdrawal.amount());
        assertEquals(-2, withdrawal.x());
    }

    @Test
    public void aCancelledTakeIsNotRecorded() {
        Lectern lectern = (Lectern) block.getState();
        lectern.getInventory().setItem(0, new ItemStack(Material.WRITABLE_BOOK));
        PlayerTakeLecternBookEvent take = new PlayerTakeLecternBookEvent(player, (LecternInventory) lectern.getInventory(), lectern);
        take.setCancelled(true);
        server.getPluginManager().callEvent(take);

        assertTrue(Recorded.events(Action.CONTAINER_WITHDRAW).isEmpty());
    }
}
