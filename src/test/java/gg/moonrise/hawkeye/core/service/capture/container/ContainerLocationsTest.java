package gg.moonrise.hawkeye.core.service.capture.container;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
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
    public void entitiesAndVirtualInventoriesHaveNoBlock() {
        InventoryHolder player = server.addPlayer("Alex");
        assertTrue(ContainerLocations.of(player).isEmpty());
        assertTrue(ContainerLocations.of((InventoryHolder) null).isEmpty());
    }
}
