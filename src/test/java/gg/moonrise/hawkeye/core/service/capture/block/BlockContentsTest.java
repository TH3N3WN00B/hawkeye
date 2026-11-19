package gg.moonrise.hawkeye.core.service.capture.block;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.core.service.capture.text.SignText;
import gg.moonrise.hawkeye.entity.payload.Payload;
import gg.moonrise.hawkeye.entity.payload.SignSides;
import gg.moonrise.hawkeye.util.ItemBytes;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

public class BlockContentsTest {

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
    public void aContainerKeepsItsNonEmptySlots() {
        Block block = world.getBlockAt(0, 64, 0);
        block.setType(Material.CHEST);
        ItemStack diamonds = new ItemStack(Material.DIAMOND, 5);
        ((Chest) block.getState()).getInventory().setItem(3, diamonds);

        Payload.Inventory inventory = (Payload.Inventory) BlockContents.of(block).orElseThrow();
        assertEquals(1, inventory.slots().size());
        assertEquals(3, inventory.slots().getFirst().index());
        assertArrayEquals(ItemBytes.serialize(diamonds), inventory.slots().getFirst().item());
    }

    @Test
    public void anEmptyContainerOrPlainBlockHasNoContents() {
        Block block = world.getBlockAt(0, 64, 0);
        block.setType(Material.BARREL);
        assertTrue(BlockContents.of(block).isEmpty());
        block.setType(Material.STONE);
        assertTrue(BlockContents.of(block).isEmpty());
    }

    @Test
    public void aSignKeepsItsTextAsJsonBeforeAndNothingAfter() {
        Block block = world.getBlockAt(0, 64, 0);
        block.setType(Material.OAK_SIGN);
        Component line = Component.text("Keep out", NamedTextColor.RED);
        ((Sign) block.getState()).getSide(Side.FRONT).line(1, line);

        Payload.Sign sign = (Payload.Sign) BlockContents.of(block).orElseThrow();
        String json = GsonComponentSerializer.gson().serialize(line);
        String empty = GsonComponentSerializer.gson().serialize(Component.empty());
        assertEquals(List.of(empty, json, empty, empty), sign.before().front());
        assertEquals(List.of(), sign.before().back());
        assertEquals(SignSides.EMPTY, sign.after());
    }

    @Test
    public void aBlankSignHasNoContents() {
        Block block = world.getBlockAt(0, 64, 0);
        block.setType(Material.OAK_SIGN);
        assertTrue(BlockContents.of(block).isEmpty());
    }

    @Test
    public void editingASideKeepsTheOther() {
        SignSides sides = new SignSides(List.of("a", "b", "c", "d"), List.of("e", "f", "g", "h"));
        assertEquals(new SignSides(List.of("1"), sides.back()), SignText.with(sides, Side.FRONT, List.of("1")));
        assertEquals(new SignSides(sides.front(), List.of()), SignText.with(sides, Side.BACK, List.of()));
    }
}
