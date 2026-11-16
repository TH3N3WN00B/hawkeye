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
import org.bukkit.block.data.BlockData;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.block.state.BlockStateMock;

public class FluidListenerTest {

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
    public void flowingWaterIsAPlaceByWater() {
        Block source = block(0, Material.WATER);
        Block torch = block(1, Material.TORCH);
        server.getPluginManager().callEvent(new BlockFromToEvent(source, torch));

        StoredEvent event = Recorded.only(Action.BLOCK_PLACE);
        assertEquals("#water", event.actor().name());
        assertEquals(source.getBlockData().getAsString(), event.subject());
        assertEquals("minecraft:torch", event.secondary());
        assertEquals(1, event.x());
    }

    @Test
    public void flowingLavaIsAPlaceByLava() {
        server.getPluginManager().callEvent(new BlockFromToEvent(block(0, Material.LAVA), block(1, Material.AIR)));

        assertEquals("#lava", Recorded.only(Action.BLOCK_PLACE).actor().name());
    }

    @Test
    public void flowWithinTheSameFluidOrOfANonFluidIsNotRecorded() {
        server.getPluginManager().callEvent(new BlockFromToEvent(block(0, Material.WATER), block(1, Material.WATER)));
        server.getPluginManager().callEvent(new BlockFromToEvent(block(2, Material.DRAGON_EGG), block(3, Material.AIR)));

        assertTrue(Recorded.events(Action.BLOCK_PLACE).isEmpty());
    }

    @Test
    public void lavaTurnedToObsidianIsAChangeByWaterAndNotNatural() {
        Block lava = block(0, Material.LAVA);
        BlockData before = lava.getBlockData();
        server.getPluginManager().callEvent(new BlockFormEvent(lava, new BlockStateMock(Material.OBSIDIAN)));

        StoredEvent event = Recorded.only(Action.BLOCK_CHANGE);
        assertEquals("#water", event.actor().name());
        assertEquals("minecraft:obsidian", event.subject());
        assertEquals(before.getAsString(), event.secondary());
        assertTrue(Recorded.events(Action.BLOCK_PLACE).isEmpty(), "the natural listener leaves fluid forms alone");
    }

    @Test
    public void waterTurnedToStoneIsBlamedOnLava() {
        server.getPluginManager().callEvent(new BlockFormEvent(block(0, Material.WATER),
                new BlockStateMock(Material.STONE)));

        assertEquals("#lava", Recorded.only(Action.BLOCK_CHANGE).actor().name());
    }

    private Block block(int x, Material material) {
        Block block = world.getBlockAt(x, 64, 0);
        block.setType(material);
        return block;
    }
}
