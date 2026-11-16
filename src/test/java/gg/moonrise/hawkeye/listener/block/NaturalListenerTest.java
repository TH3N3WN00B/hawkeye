package gg.moonrise.hawkeye.listener.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.listener.Recorded;
import gg.moonrise.hawkeye.support.TestPlugins;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.TreeType;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Snowman;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.EntityBlockFormEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.block.state.BlockStateMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

public class NaturalListenerTest {

    private ServerMock server;
    private World world;

    @BeforeEach
    public void setUp() {
        server = MockBukkit.mock();
        TestPlugins.load();
        world = server.addSimpleWorld("world");
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void decayingLeavesAreABreakByDecay() {
        Block leaves = block(0, 64, Material.OAK_LEAVES);
        String state = leaves.getBlockData().getAsString();
        server.getPluginManager().callEvent(new LeavesDecayEvent(leaves));

        StoredEvent event = Recorded.only(Action.BLOCK_BREAK);
        assertEquals("#decay", event.actor().name());
        assertEquals(state, event.subject());
    }

    @Test
    public void meltingIceIsAChangeAndFireBurningOutIsIgnored() {
        Block ice = block(0, 64, Material.ICE);
        server.getPluginManager().callEvent(new BlockFadeEvent(ice, new BlockStateMock(Material.WATER)));
        Block fire = block(1, 64, Material.FIRE);
        server.getPluginManager().callEvent(new BlockFadeEvent(fire, new BlockStateMock(Material.AIR)));

        StoredEvent event = Recorded.only(Action.BLOCK_CHANGE);
        assertEquals("#nature", event.actor().name());
        assertEquals("minecraft:ice", event.secondary());
        assertTrue(Recorded.events(Action.BLOCK_BREAK).isEmpty());
    }

    @Test
    public void formingSnowIsAPlaceAndASnowGolemsTrailCountsForTheGolem() {
        server.getPluginManager().callEvent(new BlockFormEvent(block(0, 64, Material.AIR),
                new BlockStateMock(Material.SNOW)));
        Snowman golem = world.spawn(new Location(world, 1, 64, 0), Snowman.class);
        server.getPluginManager().callEvent(new EntityBlockFormEvent(golem, block(1, 64, Material.AIR),
                new BlockStateMock(Material.SNOW)));

        List<StoredEvent> places = Recorded.events(Action.BLOCK_PLACE);
        assertEquals(2, places.size());
        assertEquals("#nature", places.get(0).actor().name());
        assertEquals("#snow_golem", places.get(1).actor().name());
    }

    @Test
    public void spreadingGrassIsAChange() {
        Block dirt = block(0, 64, Material.DIRT);
        Block grass = block(1, 64, Material.GRASS_BLOCK);
        server.getPluginManager().callEvent(new BlockSpreadEvent(dirt, grass, new BlockStateMock(Material.GRASS_BLOCK)));

        StoredEvent event = Recorded.only(Action.BLOCK_CHANGE);
        assertEquals("#nature", event.actor().name());
        assertEquals("minecraft:dirt", event.secondary());
    }

    @Test
    public void aBoneMealedTreeCountsForThePlayer() {
        PlayerMock player = server.addPlayer("Alex");
        Block sapling = block(0, 64, Material.OAK_SAPLING);
        String saplingState = sapling.getBlockData().getAsString();
        BlockState trunk = grown(sapling, Material.OAK_LOG);
        BlockState crown = grown(block(0, 65, Material.AIR), Material.OAK_LEAVES);
        server.getPluginManager().callEvent(new StructureGrowEvent(sapling.getLocation(), TreeType.TREE, true, player,
                List.of(trunk, crown)));

        StoredEvent change = Recorded.only(Action.BLOCK_CHANGE);
        assertEquals("Alex", change.actor().name());
        assertEquals(saplingState, change.secondary());
        StoredEvent place = Recorded.only(Action.BLOCK_PLACE);
        assertEquals(65, place.y());
        assertEquals("Alex", place.actor().name());
    }

    private Block block(int x, int y, Material material) {
        Block block = world.getBlockAt(x, y, 0);
        block.setType(material);
        return block;
    }

    private static BlockState grown(Block block, Material material) {
        BlockState state = block.getState().copy();
        state.setType(material);
        return state;
    }
}
