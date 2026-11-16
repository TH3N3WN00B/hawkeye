package gg.moonrise.hawkeye.listener.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.listener.Recorded;
import gg.moonrise.hawkeye.support.TestPlugins;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Door;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

public class InteractionListenerTest {

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
    public void openingADoorIsRecordedNextTickWithBothStates() {
        Block block = world.getBlockAt(0, 64, 0);
        Door closed = (Door) Material.OAK_DOOR.createBlockData();
        block.setBlockData(closed);
        rightClick(block);
        Door open = (Door) closed.clone();
        open.setOpen(true);
        block.setBlockData(open);
        server.getScheduler().performOneTick();

        StoredEvent event = Recorded.only(Action.INTERACT);
        assertEquals("Alex", event.actor().name());
        assertEquals(open.getAsString(), event.subject());
        assertEquals(closed.getAsString(), event.secondary());
    }

    @Test
    public void aUseThatChangesNothingOrAnUntrackedBlockIsNotRecorded() {
        Block door = world.getBlockAt(0, 64, 0);
        door.setType(Material.IRON_DOOR);
        rightClick(door);
        Block stone = world.getBlockAt(1, 64, 0);
        stone.setType(Material.STONE);
        rightClick(stone);
        stone.setType(Material.COBBLESTONE);
        server.getScheduler().performOneTick();

        assertTrue(Recorded.events(Action.INTERACT).isEmpty());
    }

    private void rightClick(Block block) {
        server.getPluginManager().callEvent(new PlayerInteractEvent(player,
                org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK, null, block, BlockFace.NORTH, EquipmentSlot.HAND));
    }
}
