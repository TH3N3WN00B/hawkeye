package gg.moonrise.hawkeye.listener.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.destroystokyo.paper.event.block.BlockDestroyEvent;

import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.listener.Recorded;
import gg.moonrise.hawkeye.support.TestHawkeyePlugin;
import gg.moonrise.hawkeye.support.TestPlugins;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.TurtleEgg;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Sheep;
import org.bukkit.entity.Zombie;
import org.bukkit.event.Event;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

public class EntityChangeListenerTest {

    private ServerMock server;
    private TestHawkeyePlugin plugin;
    private World world;
    private Location location;
    private PlayerMock alex;

    @BeforeEach
    public void setUp() {
        server = MockBukkit.mock();
        plugin = TestPlugins.load();
        world = server.addSimpleWorld("world");
        location = new Location(world, 0, 64, 0);
        alex = server.addPlayer("Alex");
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

    @Test
    public void aPlayerCrackingATurtleEggIsAChangeNextTick() {
        Block block = eggs(3);
        String before = block.getBlockData().getAsString();

        stepOn(block);
        block.setBlockData(egg(2));
        assertTrue(Recorded.events(Action.BLOCK_CHANGE).isEmpty(), "nothing is known before the tick");
        server.getScheduler().performOneTick();

        StoredEvent event = Recorded.only(Action.BLOCK_CHANGE);
        assertEquals("Alex", event.actor().name());
        assertEquals(egg(2).getAsString(), event.subject());
        assertEquals(before, event.secondary());
    }

    @Test
    public void aMobCrackingTheLastEggIsABreak() {
        Block block = eggs(1);

        call(new EntityInteractEvent(world.spawn(location, Zombie.class), block));
        block.setType(Material.AIR);
        server.getScheduler().performOneTick();

        StoredEvent event = Recorded.only(Action.BLOCK_BREAK);
        assertEquals("#zombie", event.actor().name());
        assertEquals(egg(1).getAsString(), event.subject());
    }

    /** The server removes the last egg with a block destroy, which the attached-block listener records. */
    @Test
    public void theLastEggDestroyedByTheServerIsRecordedOnceForThePlayer() {
        Block block = eggs(1);

        stepOn(block);
        call(new BlockDestroyEvent(block, Material.AIR.createBlockData(), block.getBlockData(), 0, false));
        block.setType(Material.AIR);
        server.getScheduler().performOneTick();

        assertEquals("Alex", Recorded.only(Action.BLOCK_BREAK).actor().name());
    }

    @Test
    public void anEggThatDidNotCrackAndOtherPhysicalInteractionsRecordNothing() {
        Block egg = eggs(2);
        Block farmland = block(1, Material.FARMLAND);

        stepOn(egg);
        stepOn(farmland);
        farmland.setType(Material.DIRT);
        server.getScheduler().performOneTick();

        assertTrue(Recorded.events(Action.BLOCK_BREAK, Action.BLOCK_PLACE, Action.BLOCK_CHANGE).isEmpty());
    }

    @Test
    public void aDeniedOrDisabledEggCrackIsNotRecorded() {
        Block denied = eggs(2);
        PlayerInteractEvent event = new PlayerInteractEvent(alex, org.bukkit.event.block.Action.PHYSICAL, null,
                denied, null);
        event.setUseInteractedBlock(Event.Result.DENY);
        call(event);
        denied.setBlockData(egg(1));
        server.getScheduler().performOneTick();
        assertTrue(Recorded.events(Action.BLOCK_CHANGE).isEmpty());

        TestPlugins.writeConfig(plugin, "logging:\n  entity-changes: false\n");
        TestPlugins.reload();
        Block off = block(2, Material.AIR);
        off.setBlockData(egg(2));
        stepOn(off);
        off.setBlockData(egg(1));
        server.getScheduler().performOneTick();
        assertTrue(Recorded.events(Action.BLOCK_CHANGE).isEmpty());
    }

    private Block eggs(int count) {
        Block block = world.getBlockAt(0, 64, 0);
        block.setBlockData(egg(count));
        return block;
    }

    private static TurtleEgg egg(int count) {
        TurtleEgg egg = (TurtleEgg) Material.TURTLE_EGG.createBlockData();
        egg.setEggs(count);
        return egg;
    }

    private void stepOn(Block block) {
        call(new PlayerInteractEvent(alex, org.bukkit.event.block.Action.PHYSICAL, null, block, null));
    }

    private void call(Event event) {
        server.getPluginManager().callEvent(event);
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
