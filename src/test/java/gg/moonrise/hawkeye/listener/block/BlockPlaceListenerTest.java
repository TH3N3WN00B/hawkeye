package gg.moonrise.hawkeye.listener.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.listener.Recorded;
import gg.moonrise.hawkeye.support.TestPlugins;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.type.Bed;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

public class BlockPlaceListenerTest {

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
    public void aPlacedBlockIsRecordedWithWhatItReplaced() {
        Block block = world.getBlockAt(1, 70, 1);
        block.setType(Material.SHORT_GRASS);
        BlockState replaced = block.getState();
        block.setType(Material.OAK_PLANKS);
        server.getPluginManager().callEvent(new BlockPlaceEvent(block, replaced, block.getRelative(BlockFace.DOWN),
                new ItemStack(Material.OAK_PLANKS), player, true, EquipmentSlot.HAND));

        StoredEvent event = Recorded.only(Action.BLOCK_PLACE);
        assertEquals("Alex", event.actor().name());
        assertEquals("minecraft:oak_planks", event.subject());
        assertEquals("minecraft:short_grass", event.secondary());
        assertEquals(70, event.y());
    }

    @Test
    public void everyBlockOfAMultiPlaceIsRecordedOnce() {
        Block foot = world.getBlockAt(0, 64, 0);
        Block head = world.getBlockAt(1, 64, 0);
        List<BlockState> replaced = List.of(foot.getState(), head.getState());
        foot.setBlockData(bed(Bed.Part.FOOT));
        head.setBlockData(bed(Bed.Part.HEAD));
        server.getPluginManager().callEvent(new BlockMultiPlaceEvent(replaced, foot.getRelative(BlockFace.DOWN),
                new ItemStack(Material.RED_BED), player, true, EquipmentSlot.HAND));

        List<StoredEvent> places = Recorded.events(Action.BLOCK_PLACE);
        assertEquals(2, places.size());
        assertEquals(bed(Bed.Part.FOOT).getAsString(), places.get(0).subject());
        assertEquals(0, places.get(0).x());
        assertEquals(bed(Bed.Part.HEAD).getAsString(), places.get(1).subject());
        assertEquals(1, places.get(1).x());
        assertTrue(places.stream().allMatch(event -> "minecraft:air".equals(event.secondary())));
    }

    @Test
    public void aPlaceThatCannotBuildRecordsNothing() {
        Block block = world.getBlockAt(0, 64, 0);
        BlockState replaced = block.getState();
        block.setType(Material.STONE);
        server.getPluginManager().callEvent(new BlockPlaceEvent(block, replaced, block.getRelative(BlockFace.DOWN),
                new ItemStack(Material.STONE), player, false, EquipmentSlot.HAND));

        assertTrue(Recorded.events(Action.BLOCK_PLACE).isEmpty());
    }

    private static Bed bed(Bed.Part part) {
        Bed bed = (Bed) Material.RED_BED.createBlockData();
        bed.setFacing(BlockFace.EAST);
        bed.setPart(part);
        return bed;
    }
}
