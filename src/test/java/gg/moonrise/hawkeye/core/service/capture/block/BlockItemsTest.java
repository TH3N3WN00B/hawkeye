package gg.moonrise.hawkeye.core.service.capture.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Jukebox;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

public class BlockItemsTest {

    private World world;

    @BeforeEach
    public void setUp() {
        ServerMock server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void shelvesHoldItemsWhereTheServerHasThem() {
        BlockItems items = new BlockItems(new PaperShelves());
        Block shelf = block(1, Material.OAK_SHELF);

        assertTrue(items.holdsItems(Material.OAK_SHELF));
        assertEquals(3, items.contents(shelf.getState()).orElseThrow().length);
    }

    @Test
    public void withoutShelvesTheOtherBlocksStillHoldItems() {
        BlockItems items = new BlockItems(new NoShelves());
        Block jukebox = block(2, Material.JUKEBOX);
        Jukebox state = (Jukebox) jukebox.getState();
        state.setRecord(new ItemStack(Material.MUSIC_DISC_CAT));

        assertTrue(items.holdsItems(Material.JUKEBOX));
        assertTrue(items.holdsItems(Material.DECORATED_POT));
        assertFalse(items.holdsItems(Material.OAK_SHELF));
        assertTrue(items.contents(block(3, Material.OAK_SHELF).getState()).isEmpty());
        assertEquals(Material.MUSIC_DISC_CAT, items.contents(state).orElseThrow()[0].getType());
    }

    private Block block(int x, Material type) {
        Block block = world.getBlockAt(x, 64, 0);
        block.setType(type);
        return block;
    }
}
