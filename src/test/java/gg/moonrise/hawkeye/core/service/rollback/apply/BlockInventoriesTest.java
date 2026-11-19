package gg.moonrise.hawkeye.core.service.rollback.apply;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.entity.payload.Payload;
import gg.moonrise.hawkeye.support.TestPlugins;
import gg.moonrise.hawkeye.util.ItemBytes;
import io.papermc.paper.block.TileStateInventoryHolder;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Jukebox;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * Recorded contents put back into blocks that hold items. Called directly: MockBukkit's
 * {@code setBlockData} keeps the old block state object, so the contents of a block a {@code SetBlock}
 * change puts back cannot be seen there.
 */
public class BlockInventoriesTest {

    private World world;

    @BeforeEach
    public void setUp() {
        ServerMock server = MockBukkit.mock();
        TestPlugins.load();
        world = server.addSimpleWorld("world");
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void aChiseledBookshelfGetsItsRecordedSlotsBack() {
        Block block = block(1, Material.CHISELED_BOOKSHELF);
        inventory(block).setItem(5, new ItemStack(Material.BOOK));

        BlockInventories.fill(block, List.of(slot(0, Material.ENCHANTED_BOOK), slot(3, Material.WRITTEN_BOOK),
                slot(40, Material.BOOK)));

        Inventory inventory = inventory(block);
        assertEquals(Material.ENCHANTED_BOOK, inventory.getItem(0).getType());
        assertEquals(Material.WRITTEN_BOOK, inventory.getItem(3).getType());
        assertTrue(inventory.getItem(5) == null || inventory.getItem(5).isEmpty());
    }

    @Test
    public void aJukeboxGetsItsRecordBack() {
        Block block = block(2, Material.JUKEBOX);

        BlockInventories.fill(block, List.of(slot(0, Material.MUSIC_DISC_PIGSTEP)));

        assertEquals(Material.MUSIC_DISC_PIGSTEP, ((Jukebox) block.getState()).getRecord().getType());
    }

    @Test
    public void aBlockWithoutItemsIsLeftAlone() {
        Block block = block(3, Material.STONE);

        BlockInventories.fill(block, List.of(slot(0, Material.BOOK)));

        assertTrue(BlockInventories.adjust(block, new ItemStack(Material.BOOK), 1).isEmpty());
        assertEquals(Material.STONE, block.getType());
    }

    private Block block(int x, Material material) {
        Block block = world.getBlockAt(x, 64, 0);
        block.setType(material);
        return block;
    }

    private static Inventory inventory(Block block) {
        return ((TileStateInventoryHolder) block.getState()).getInventory();
    }

    private static Payload.Slot slot(int index, Material material) {
        return new Payload.Slot(index, ItemBytes.serialize(new ItemStack(material)));
    }
}
