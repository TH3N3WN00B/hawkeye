package gg.moonrise.hawkeye.listener.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.listener.Recorded;
import gg.moonrise.hawkeye.support.TestPlugins;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Campfire;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.block.state.BlockStateMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

public class FireListenerTest {

    private ServerMock server;
    private World world;
    private PlayerMock player;

    @BeforeEach
    public void setUp() {
        server = MockBukkit.mock();
        TestPlugins.load();
        world = server.addSimpleWorld("world");
        player = server.addPlayer("Alex");
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void aBurntBlockIsABreakByFire() {
        Block planks = world.getBlockAt(0, 64, 0);
        planks.setType(Material.OAK_PLANKS);
        server.getPluginManager().callEvent(new BlockBurnEvent(planks, null));

        StoredEvent event = Recorded.only(Action.BLOCK_BREAK);
        assertEquals("#fire", event.actor().name());
        assertEquals("minecraft:oak_planks", event.subject());
    }

    @Test
    public void fireLitByAPlayerIsAPlaceByThePlayer() {
        Block block = world.getBlockAt(0, 64, 0);
        server.getPluginManager().callEvent(new BlockIgniteEvent(block, BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL,
                player));

        StoredEvent event = Recorded.only(Action.BLOCK_PLACE);
        assertEquals("Alex", event.actor().name());
        assertEquals(Material.FIRE.createBlockData().getAsString(), event.subject());
        assertEquals("minecraft:air", event.secondary());
    }

    @Test
    public void fireOnSoulSoilIsSoulFireAndLavaIsTheCause() {
        Block block = world.getBlockAt(0, 64, 0);
        block.getRelative(BlockFace.DOWN).setType(Material.SOUL_SOIL);
        server.getPluginManager().callEvent(new BlockIgniteEvent(block, BlockIgniteEvent.IgniteCause.LAVA,
                world.getBlockAt(1, 64, 0)));

        StoredEvent event = Recorded.only(Action.BLOCK_PLACE);
        assertEquals("#lava", event.actor().name());
        assertEquals("minecraft:soul_fire", event.subject());
    }

    @Test
    public void aLitCampfireIsAChange() {
        Block block = world.getBlockAt(0, 64, 0);
        Campfire campfire = (Campfire) Material.CAMPFIRE.createBlockData();
        campfire.setLit(false);
        block.setBlockData(campfire);
        server.getPluginManager().callEvent(new BlockIgniteEvent(block, BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL,
                player));

        StoredEvent event = Recorded.only(Action.BLOCK_CHANGE);
        assertEquals(campfire.getAsString(), event.secondary());
        assertTrue(event.subject().contains("lit=true"), event.subject());
    }

    @Test
    public void spreadingFireIsRecordedOnceAsASpread() {
        Block block = world.getBlockAt(0, 64, 0);
        Block source = world.getBlockAt(1, 64, 0);
        source.setType(Material.FIRE);
        server.getPluginManager().callEvent(new BlockIgniteEvent(block, BlockIgniteEvent.IgniteCause.SPREAD, source));
        server.getPluginManager().callEvent(new BlockSpreadEvent(block, source, new BlockStateMock(Material.FIRE)));

        StoredEvent event = Recorded.only(Action.BLOCK_PLACE);
        assertEquals("#fire", event.actor().name());
        assertTrue(Recorded.events(Action.BLOCK_CHANGE).isEmpty(), "natural spread ignores fire");
    }
}
