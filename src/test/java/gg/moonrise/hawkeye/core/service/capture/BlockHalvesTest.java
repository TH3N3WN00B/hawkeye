package gg.moonrise.hawkeye.core.service.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.Stairs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

public class BlockHalvesTest {

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
    public void aDoorHalfFindsTheOtherHalf() {
        Block bottom = door(0, Bisected.Half.BOTTOM);
        Block top = door(1, Bisected.Half.TOP);
        assertEquals(top, BlockHalves.otherHalf(bottom).orElseThrow());
        assertEquals(bottom, BlockHalves.otherHalf(top).orElseThrow());
    }

    @Test
    public void aBedPartFindsTheOtherPartAlongItsFacing() {
        Block foot = bed(0, Bed.Part.FOOT);
        Block head = bed(1, Bed.Part.HEAD);
        assertEquals(head, BlockHalves.otherHalf(foot).orElseThrow());
        assertEquals(foot, BlockHalves.otherHalf(head).orElseThrow());
    }

    @Test
    public void stairsAndLoneHalvesHaveNoOtherHalf() {
        Block lower = world.getBlockAt(0, 64, 0);
        Stairs stairs = (Stairs) Material.OAK_STAIRS.createBlockData();
        lower.setBlockData(stairs);
        Stairs upper = (Stairs) Material.OAK_STAIRS.createBlockData();
        upper.setHalf(Bisected.Half.TOP);
        lower.getRelative(BlockFace.UP).setBlockData(upper);
        assertTrue(BlockHalves.otherHalf(lower).isEmpty());

        Block lone = door(10, Bisected.Half.BOTTOM);
        assertTrue(BlockHalves.otherHalf(lone).isEmpty());
        assertTrue(BlockHalves.otherHalf(world.getBlockAt(5, 64, 5)).isEmpty());
    }

    private Block door(int dy, Bisected.Half half) {
        Door data = (Door) Material.OAK_DOOR.createBlockData();
        data.setHalf(half);
        Block block = world.getBlockAt(0, 64 + dy, 0);
        block.setBlockData(data);
        return block;
    }

    private Block bed(int dx, Bed.Part part) {
        Bed data = (Bed) Material.RED_BED.createBlockData();
        data.setFacing(BlockFace.EAST);
        data.setPart(part);
        Block block = world.getBlockAt(dx, 64, 0);
        block.setBlockData(data);
        return block;
    }
}
