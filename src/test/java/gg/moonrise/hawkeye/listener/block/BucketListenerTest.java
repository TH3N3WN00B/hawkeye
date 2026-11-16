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
import org.bukkit.block.data.type.Slab;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

public class BucketListenerTest {

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
    public void emptyingABucketPlacesItsFluid() {
        Block block = world.getBlockAt(0, 64, 0);
        empty(block, Material.LAVA_BUCKET);

        StoredEvent event = Recorded.only(Action.BLOCK_PLACE);
        assertEquals("Alex", event.actor().name());
        assertEquals(Material.LAVA.createBlockData().getAsString(), event.subject());
        assertEquals("minecraft:air", event.secondary());
    }

    @Test
    public void emptyingWaterIntoAWaterloggableBlockIsAChange() {
        Block block = world.getBlockAt(0, 64, 0);
        Slab slab = (Slab) Material.OAK_SLAB.createBlockData();
        block.setBlockData(slab);
        empty(block, Material.WATER_BUCKET);

        StoredEvent event = Recorded.only(Action.BLOCK_CHANGE);
        assertEquals(slab.getAsString(), event.secondary());
        assertTrue(event.subject().contains("waterlogged=true"), event.subject());
    }

    @Test
    public void fillingABucketRemovesTheFluid() {
        Block block = world.getBlockAt(0, 64, 0);
        block.setType(Material.WATER);
        String water = block.getBlockData().getAsString();
        server.getPluginManager().callEvent(new PlayerBucketFillEvent(player, block, block, BlockFace.UP,
                Material.BUCKET, new ItemStack(Material.BUCKET), EquipmentSlot.HAND));

        StoredEvent event = Recorded.only(Action.BLOCK_BREAK);
        assertEquals(water, event.subject());
        assertEquals("minecraft:air", event.secondary());
    }

    @Test
    public void cauldronsAreLeftToTheirOwnEvent() {
        Block cauldron = world.getBlockAt(0, 64, 0);
        cauldron.setType(Material.CAULDRON);
        empty(cauldron, Material.WATER_BUCKET);
        cauldron.setType(Material.WATER_CAULDRON);
        server.getPluginManager().callEvent(new PlayerBucketFillEvent(player, cauldron, cauldron, BlockFace.UP,
                Material.BUCKET, new ItemStack(Material.BUCKET), EquipmentSlot.HAND));

        assertTrue(Recorded.events(Action.BLOCK_PLACE, Action.BLOCK_BREAK, Action.BLOCK_CHANGE).isEmpty());
    }

    private void empty(Block block, Material bucket) {
        server.getPluginManager().callEvent(new PlayerBucketEmptyEvent(player, block,
                block.getRelative(BlockFace.DOWN), BlockFace.UP, bucket, new ItemStack(bucket), EquipmentSlot.HAND));
    }
}
