package gg.moonrise.hawkeye.core.service.capture.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.core.service.capture.Actors;
import gg.moonrise.hawkeye.core.service.capture.CauseTracker;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.hawkeye.support.TestPlugins;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

public class BlockCaptureTest {

    private static final Actor ALEX = Actor.player(UUID.fromString("00000000-0000-0000-0000-00000000a1e5"), "Alex");

    private World world;
    private BlockCapture blocks;
    private CauseTracker causes;

    @BeforeEach
    public void setUp() {
        ServerMock server = MockBukkit.mock();
        TestPlugins.load();
        world = server.addSimpleWorld("world");
        blocks = TestPlugins.bean(BlockCapture.class);
        causes = TestPlugins.bean(CauseTracker.class);
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void aBrokenBlockIsRememberedAroundItAndUpItsColumn() {
        Block block = block(0, Material.STONE);
        blocks.broken(ALEX, block);

        assertEquals(ALEX, causes.recentCause(block.getRelative(BlockFace.NORTH)).orElseThrow());
        assertEquals(ALEX, causes.recentCause(block.getRelative(0, 3, 0)).orElseThrow());
    }

    @Test
    public void aClassifiedOrAppliedBreakIsRememberedButAPlaceIsNot() {
        Block changed = block(0, Material.DIRT);
        blocks.changed(ALEX, changed, changed.getBlockData(), Material.AIR.createBlockData());
        Block applied = block(10, Material.RAIL);
        blocks.applied(ALEX, applied, new BlockChange(Action.BLOCK_BREAK, applied.getBlockData(),
                Material.AIR.createBlockData()));
        Block placed = block(20, Material.AIR);
        blocks.changed(ALEX, placed, placed.getBlockData(), Material.STONE.createBlockData());

        assertTrue(causes.recentCause(changed.getRelative(BlockFace.UP)).isPresent());
        assertTrue(causes.recentCause(applied.getRelative(BlockFace.UP)).isPresent());
        assertTrue(causes.recentCause(placed.getRelative(BlockFace.UP)).isEmpty());
    }

    @Test
    public void anUnknownCauseIsNotRemembered() {
        Block block = block(0, Material.TORCH);
        blocks.broken(Actors.PHYSICS, block);

        assertTrue(causes.recentCause(block.getRelative(BlockFace.UP)).isEmpty());
    }

    private Block block(int x, Material material) {
        Block block = world.getBlockAt(x, 64, 0);
        block.setType(material);
        return block;
    }
}
