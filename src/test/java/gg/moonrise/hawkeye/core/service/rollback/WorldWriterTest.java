package gg.moonrise.hawkeye.core.service.rollback;

import static org.junit.jupiter.api.Assertions.assertEquals;

import gg.moonrise.hawkeye.entity.payload.SignSides;
import gg.moonrise.hawkeye.support.TestPlugins;
import gg.moonrise.hawkeye.util.BlockPositions;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/** The world side of a planned change, on MockBukkit blocks. */
public class WorldWriterTest {

    private World world;
    private WorldWriter writer;

    @BeforeEach
    public void setUp() {
        ServerMock server = MockBukkit.mock();
        TestPlugins.load();
        world = server.addSimpleWorld("world");
        writer = TestPlugins.bean(WorldWriter.class);
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void aBlockStateIsSet() {
        Block block = world.getBlockAt(1, 64, 1);
        assertEquals(WorldWriter.Effect.BLOCK, writer.apply(world, new PlannedChange.SetBlock(position(1, 64, 1),
                "minecraft:stone", SignPatch.NONE, List.of())));
        assertEquals(Material.STONE, block.getType());
    }

    @Test
    public void anUnknownBlockStateChangesNothing() {
        Block block = world.getBlockAt(1, 64, 1);
        block.setType(Material.DIRT);
        assertEquals(WorldWriter.Effect.NONE, writer.apply(world, new PlannedChange.SetBlock(position(1, 64, 1),
                "minecraft:not_a_block", SignPatch.NONE, List.of())));
        assertEquals(Material.DIRT, block.getType());
    }

    @Test
    public void signTextIsWrittenOnTheFlaggedSidesOnly() {
        Block block = world.getBlockAt(2, 64, 2);
        block.setType(Material.OAK_SIGN);
        Sign sign = (Sign) block.getState();
        sign.getSide(Side.FRONT).line(3, Component.text("gone"));
        sign.getSide(Side.BACK).line(0, Component.text("kept"));
        sign.update(true, false);

        SignPatch patch = new SignPatch(new SignSides(List.of("{\"text\":\"hello\"}", "plain"), List.of()), true,
                false);
        assertEquals(WorldWriter.Effect.BLOCK, writer.apply(world, new PlannedChange.SetSignText(position(2, 64, 2),
                patch)));

        Sign written = (Sign) block.getState();
        assertEquals(Component.text("hello"), written.getSide(Side.FRONT).line(0));
        assertEquals(Component.text("plain"), written.getSide(Side.FRONT).line(1));
        assertEquals(Component.empty(), written.getSide(Side.FRONT).line(3));
        assertEquals(Component.text("kept"), written.getSide(Side.BACK).line(0));
    }

    @Test
    public void signTextNeedsASign() {
        assertEquals(WorldWriter.Effect.NONE, writer.apply(world, new PlannedChange.SetSignText(position(2, 64, 2),
                SignPatch.full(SignSides.EMPTY))));
    }

    @Test
    public void containerItemsAreTakenOutAndPutBack() {
        Block block = world.getBlockAt(3, 64, 3);
        block.setType(Material.CHEST);
        Container chest = (Container) block.getState();
        chest.getInventory().addItem(new ItemStack(Material.DIAMOND, 10));

        assertEquals(WorldWriter.Effect.ITEMS, writer.apply(world, new PlannedChange.AdjustItems(position(3, 64, 3),
                "minecraft:diamond", new byte[0], -4)));
        assertEquals(6, count(block, Material.DIAMOND));
        assertEquals(WorldWriter.Effect.ITEMS, writer.apply(world, new PlannedChange.AdjustItems(position(3, 64, 3),
                "minecraft:diamond", new byte[0], 70)));
        assertEquals(76, count(block, Material.DIAMOND));
        assertEquals(WorldWriter.Effect.NONE, writer.apply(world, new PlannedChange.AdjustItems(position(4, 64, 3),
                "minecraft:diamond", new byte[0], 1)));
    }

    private static int count(Block block, Material material) {
        int total = 0;
        for (ItemStack stack : ((Container) block.getState()).getInventory().getContents()) {
            if (stack != null && stack.getType() == material) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    private static long position(int x, int y, int z) {
        return BlockPositions.pack(x, y, z);
    }
}
