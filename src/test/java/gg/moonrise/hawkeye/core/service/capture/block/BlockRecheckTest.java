package gg.moonrise.hawkeye.core.service.capture.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.support.TestPlugins;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

public class BlockRecheckTest {

    private ServerMock server;
    private World world;
    private BlockRecheck recheck;

    @BeforeEach
    public void setUp() {
        server = MockBukkit.mock();
        TestPlugins.load();
        world = server.addSimpleWorld("world");
        recheck = TestPlugins.bean(BlockRecheck.class);
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void onlyBlocksThatChangedByTheNextTickAreReported() {
        Block changed = block(0, Material.STONE);
        Block same = block(1, Material.DIRT);
        Block otherChunk = block(40, Material.SAND);
        List<String> reported = new ArrayList<>();
        recheck.afterTick(List.of(BlockRecheck.Snapshot.of(changed), BlockRecheck.Snapshot.of(same),
                BlockRecheck.Snapshot.of(otherChunk)), (block, before, after) ->
                reported.add(block.getX() + ":" + before.getMaterial() + ">" + after.getMaterial()));
        changed.setType(Material.AIR);
        otherChunk.setType(Material.GRAVEL);

        assertTrue(reported.isEmpty(), "nothing is read before the tick");
        server.getScheduler().performOneTick();

        assertEquals(List.of("0:STONE>AIR", "40:SAND>GRAVEL"), reported);
    }

    private Block block(int x, Material material) {
        Block block = world.getBlockAt(x, 64, 0);
        block.setType(material);
        return block;
    }
}
