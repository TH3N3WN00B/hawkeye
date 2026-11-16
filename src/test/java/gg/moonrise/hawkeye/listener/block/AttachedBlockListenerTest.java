package gg.moonrise.hawkeye.listener.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.destroystokyo.paper.event.block.BlockDestroyEvent;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.entity.payload.Payload;
import gg.moonrise.hawkeye.listener.Recorded;
import gg.moonrise.hawkeye.support.TestHawkeyePlugin;
import gg.moonrise.hawkeye.support.TestPlugins;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.ExplosionResult;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Creeper;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

public class AttachedBlockListenerTest {

    private ServerMock server;
    private TestHawkeyePlugin plugin;
    private World world;
    private PlayerMock player;

    @BeforeEach
    public void setUp() {
        server = MockBukkit.mock();
        plugin = TestPlugins.load();
        world = server.addSimpleWorld("world");
        player = server.addPlayer("Alex");
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void aTorchThatFallsOffABlockAPlayerBrokeCountsForThePlayer() {
        Block support = block(0, 64, Material.STONE);
        Block torch = block(0, 65, Material.TORCH);
        breakBlock(support);
        destroy(torch);

        StoredEvent event = breakAt(65);
        assertEquals("Alex", event.actor().name());
        assertEquals("minecraft:torch", event.subject());
        assertEquals("minecraft:air", event.secondary());
    }

    @Test
    public void aCascadeInheritsTheCause() {
        Block support = block(0, 64, Material.STONE);
        Block first = block(1, 64, Material.WALL_TORCH);
        Block second = block(2, 64, Material.WHITE_CARPET);
        breakBlock(support);
        destroy(first);
        first.setType(Material.AIR);
        destroy(second);

        List<StoredEvent> breaks = Recorded.events(Action.BLOCK_BREAK);
        assertEquals(3, breaks.size());
        assertEquals(2, breaks.get(2).x());
        assertEquals("Alex", breaks.get(2).actor().name(), "two blocks away, through the torch");
    }

    @Test
    public void aBlockWithNoKnownCauseIsPhysicsAndKeepsItsText() {
        Block sign = block(0, 64, Material.OAK_SIGN);
        ((Sign) sign.getState()).getSide(Side.FRONT).line(0, Component.text("Hello"));
        destroy(sign);

        StoredEvent event = Recorded.only(Action.BLOCK_BREAK);
        assertEquals("#physics", event.actor().name());
        assertTrue(event.payload().orElseThrow() instanceof Payload.Sign);
    }

    @Test
    public void aTorchBlownOffByAnExplosionCountsForTheExploder() {
        Block wall = block(1, 64, Material.STONE);
        Block torch = block(2, 64, Material.WALL_TORCH);
        Creeper creeper = world.spawn(new Location(world, 1, 64, 0), Creeper.class);
        server.getPluginManager().callEvent(new EntityExplodeEvent(creeper, creeper.getLocation(),
                new ArrayList<>(List.of(wall)), 1, ExplosionResult.DESTROY));
        destroy(torch);

        assertEquals("#creeper", Recorded.events(Action.BLOCK_BREAK).stream().filter(event -> event.x() == 2)
                .findFirst().orElseThrow().actor().name());
    }

    @Test
    public void aChangeAlreadyRecordedFireOrNothingChangingIsSkipped() {
        Block torch = block(0, 64, Material.TORCH);
        server.getPluginManager().callEvent(new BlockBreakEvent(torch, player));
        destroy(torch);
        destroy(block(1, 64, Material.FIRE));
        Block stone = block(2, 64, Material.STONE);
        server.getPluginManager().callEvent(new BlockDestroyEvent(stone, stone.getBlockData(), stone.getBlockData(),
                0, true));

        List<StoredEvent> events = Recorded.events(Action.BLOCK_BREAK, Action.BLOCK_PLACE, Action.BLOCK_CHANGE);
        assertEquals(1, events.size(), events.toString());
    }

    @Test
    public void aRailThatVanishesWithoutAnEventCountsForThePlayerNextTick() {
        Block support = block(0, 64, Material.STONE);
        Block rail = block(0, 65, Material.RAIL);
        Block side = block(1, 64, Material.DIRT);
        breakBlock(support);
        rail.setType(Material.AIR);
        assertEquals(1, Recorded.events(Action.BLOCK_BREAK).size(), "nothing is known before the tick");
        server.getScheduler().performOneTick();

        StoredEvent event = breakAt(65);
        assertEquals("Alex", event.actor().name());
        assertTrue(event.subject().startsWith("minecraft:rail"), event.subject());
        assertEquals(Material.DIRT, side.getType());
        assertEquals(2, Recorded.events(Action.BLOCK_BREAK).size(), "the untouched neighbour is not recorded");
    }

    @Test
    public void onlyAVanishedNeighbourThatNothingElseRecordedCountsNextTick() {
        Block support = block(0, 64, Material.STONE);
        Block torch = block(0, 65, Material.TORCH);
        Block grass = block(1, 64, Material.GRASS_BLOCK);
        Block fire = block(-1, 64, Material.FIRE);
        Block moving = block(0, 63, Material.MOVING_PISTON);
        breakBlock(support);
        destroy(torch);
        torch.setType(Material.AIR);
        grass.setType(Material.DIRT);
        fire.setType(Material.AIR);
        moving.setType(Material.AIR);
        server.getScheduler().performOneTick();

        assertEquals(2, Recorded.events(Action.BLOCK_BREAK).size());
        assertTrue(Recorded.events(Action.BLOCK_CHANGE).isEmpty());
    }

    @Test
    public void attachedBlocksOffRecordsNeither() {
        TestPlugins.writeConfig(plugin, "logging:\n  attached-blocks: false\n");
        TestPlugins.reload();
        Block support = block(0, 64, Material.STONE);
        Block torch = block(0, 65, Material.TORCH);
        Block rail = block(1, 64, Material.RAIL);
        breakBlock(support);
        destroy(torch);
        rail.setType(Material.AIR);
        server.getScheduler().performOneTick();

        assertEquals(1, Recorded.events(Action.BLOCK_BREAK).size());
    }

    private StoredEvent breakAt(int y) {
        return Recorded.events(Action.BLOCK_BREAK).stream().filter(event -> event.y() == y).findFirst()
                .orElseThrow(() -> new AssertionError("no break at y " + y + ": " + Recorded.events(Action.BLOCK_BREAK)));
    }

    private void breakBlock(Block block) {
        server.getPluginManager().callEvent(new BlockBreakEvent(block, player));
        block.setType(Material.AIR);
    }

    private void destroy(Block block) {
        server.getPluginManager().callEvent(new BlockDestroyEvent(block, Material.AIR.createBlockData(),
                block.getBlockData(), 0, true));
    }

    private Block block(int x, int y, Material material) {
        Block block = world.getBlockAt(x, y, 0);
        block.setType(material);
        return block;
    }
}
