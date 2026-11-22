package gg.moonrise.hawkeye.core.service.capture.container;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Donkey;
import org.bukkit.entity.Horse;
import org.bukkit.entity.Villager;
import org.bukkit.entity.boat.OakChestBoat;
import org.bukkit.entity.minecart.HopperMinecart;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.inventory.InventoryHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

public class ContainerLocationsTest {

    private ServerMock server;
    private World world;

    @BeforeEach
    public void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void aContainerBlockIsRecordedAtItsBlock() {
        Block block = world.getBlockAt(3, 64, -7);
        block.setType(Material.BARREL);
        assertEquals(block.getLocation(), ContainerLocations.of((Container) block.getState()).orElseThrow());
    }

    @Test
    public void entitiesThatCarryAContainerAreRecordedAtTheirBlock() {
        Location at = new Location(world, 10.7, 65.2, -4.3);
        Location block = new Location(world, 10, 65, -5);
        assertEquals(block, ContainerLocations.of(world.spawn(at, StorageMinecart.class)).orElseThrow());
        assertEquals(block, ContainerLocations.of(world.spawn(at, HopperMinecart.class)).orElseThrow());
        assertEquals(block, ContainerLocations.of(world.spawn(at, OakChestBoat.class)).orElseThrow());
        assertEquals(block, ContainerLocations.of(world.spawn(at, Donkey.class)).orElseThrow());
    }

    @Test
    public void otherEntitiesAndVirtualInventoriesHaveNoBlock() {
        InventoryHolder player = server.addPlayer("Alex");
        assertTrue(ContainerLocations.of(player).isEmpty());
        assertTrue(ContainerLocations.of(world.spawn(new Location(world, 0, 64, 0), Villager.class)).isEmpty());
        assertTrue(ContainerLocations.of(world.spawn(new Location(world, 0, 64, 0), Horse.class)).isEmpty());
        assertTrue(ContainerLocations.of((InventoryHolder) null).isEmpty());
    }
}
