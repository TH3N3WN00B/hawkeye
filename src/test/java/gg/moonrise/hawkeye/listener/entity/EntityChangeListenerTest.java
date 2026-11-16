package gg.moonrise.hawkeye.listener.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.listener.Recorded;
import gg.moonrise.hawkeye.support.TestPlugins;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Sheep;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

public class EntityChangeListenerTest {

    private ServerMock server;
    private World world;
    private Location location;

    @BeforeEach
    public void setUp() {
        server = MockBukkit.mock();
        TestPlugins.load();
        world = server.addSimpleWorld("world");
        location = new Location(world, 0, 64, 0);
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void anEndermanTakingABlockIsABreak() {
        Block block = block(0, Material.DIRT);
        change(world.spawn(location, Enderman.class), block, Material.AIR);

        StoredEvent event = Recorded.only(Action.BLOCK_BREAK);
        assertEquals("#enderman", event.actor().name());
        assertEquals("minecraft:dirt", event.subject());
        assertEquals("minecraft:air", event.secondary());
    }

    @Test
    public void anEndermanPuttingABlockDownIsAPlace() {
        change(world.spawn(location, Enderman.class), block(0, Material.AIR), Material.DIRT);

        StoredEvent event = Recorded.only(Action.BLOCK_PLACE);
        assertEquals("minecraft:dirt", event.subject());
        assertEquals("minecraft:air", event.secondary());
    }

    @Test
    public void aSheepEatingGrassIsAChange() {
        change(world.spawn(location, Sheep.class), block(0, Material.GRASS_BLOCK), Material.DIRT);

        StoredEvent event = Recorded.only(Action.BLOCK_CHANGE);
        assertEquals("#sheep", event.actor().name());
        assertEquals("minecraft:dirt", event.subject());
        assertEquals(Material.GRASS_BLOCK.createBlockData().getAsString(), event.secondary());
    }

    private Block block(int x, Material material) {
        Block block = world.getBlockAt(x, 64, 0);
        block.setType(material);
        return block;
    }

    private void change(Entity entity, Block block, Material to) {
        server.getPluginManager().callEvent(new EntityChangeBlockEvent(entity, block, to.createBlockData()));
    }
}
