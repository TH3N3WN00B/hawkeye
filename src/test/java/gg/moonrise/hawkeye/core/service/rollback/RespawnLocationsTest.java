package gg.moonrise.hawkeye.core.service.rollback;

import static org.junit.jupiter.api.Assertions.assertEquals;

import gg.moonrise.hawkeye.support.TestPlugins;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Zombie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/** Where respawned entities go, and the anchor block of even-sized paintings. */
public class RespawnLocationsTest {

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
    public void anEntityInItsRecordedBlockKeepsItsExactPositionAndRotation() {
        Zombie zombie = world.spawn(new Location(world, 3.2, 64.4, -1.7, 90, 10), Zombie.class);

        Location location = RespawnLocations.of(zombie, world, 3, 64, -2);

        assertEquals(new Location(world, 3.2, 64.4, -1.7, 90, 10), location);
    }

    @Test
    public void anEntityElsewhereIsSpawnedAtTheRecordedBlocksCentre() {
        Zombie zombie = world.spawn(new Location(world, 30, 64, 30), Zombie.class);

        assertEquals(new Location(world, 3.5, 64, -1.5), RespawnLocations.of(zombie, world, 3, 64, -2));
    }

    @Test
    public void anOddSizedPaintingStaysInTheBlockOfItsCentre() {
        Location centre = new Location(world, 10.5, 70.5, 5.96875);

        assertEquals(centre, RespawnLocations.anchor(centre, BlockFace.NORTH, 1, 1));
        assertEquals(centre, RespawnLocations.anchor(centre, BlockFace.NORTH, 3, 3));
    }

    @Test
    public void anEvenSizedPaintingIsMovedBackIntoItsAnchorBlock() {
        // A 2x2 painting anchored at (10, 70, 5): its centre is half a block counter-clockwise of its
        // facing and half a block up, on the edge to the neighbouring blocks.
        assertAnchor(BlockFace.SOUTH, new Location(world, 11.0, 71.0, 5.03125), 10, 70, 5);
        assertAnchor(BlockFace.NORTH, new Location(world, 10.0, 71.0, 5.96875), 10, 70, 5);
        assertAnchor(BlockFace.EAST, new Location(world, 10.03125, 71.0, 5.0), 10, 70, 5);
        assertAnchor(BlockFace.WEST, new Location(world, 10.96875, 71.0, 6.0), 10, 70, 5);
    }

    @Test
    public void onlyTheEvenSideOfAPaintingIsCorrected() {
        Location wide = RespawnLocations.anchor(new Location(world, 11.0, 70.5, 5.03125), BlockFace.SOUTH, 2, 1);
        assertEquals(new Location(world, 10.5, 70.5, 5.03125), wide);
        Location tall = RespawnLocations.anchor(new Location(world, 10.5, 71.0, 5.03125), BlockFace.SOUTH, 1, 2);
        assertEquals(new Location(world, 10.5, 70.5, 5.03125), tall);
    }

    private static void assertAnchor(BlockFace facing, Location centre, int x, int y, int z) {
        Location anchor = RespawnLocations.anchor(centre, facing, 2, 2);
        assertEquals(x, anchor.getBlockX(), facing + " x");
        assertEquals(y, anchor.getBlockY(), facing + " y");
        assertEquals(z, anchor.getBlockZ(), facing + " z");
    }
}
