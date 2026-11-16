package gg.moonrise.hawkeye.listener.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.listener.Recorded;
import gg.moonrise.hawkeye.support.TestHawkeyePlugin;
import gg.moonrise.hawkeye.support.TestPlugins;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.TreeType;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

public class GrowthListenerTest {

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
    public void ripeWheatIsOneCompactGrowEventAndAnIntermediateStageIsNothing() {
        Block ripening = crop(0, 6);
        grow(ripening, wheat(7));
        grow(crop(1, 2), wheat(3));

        StoredEvent event = Recorded.only(Action.BLOCK_GROW);
        assertEquals("#growth", event.actor().name());
        assertEquals(wheat(7).getAsString(), event.subject());
        assertNull(event.secondary());
        assertTrue(event.payload().isEmpty());
        assertEquals(0, event.x());
    }

    @Test
    public void aMelonGrowingNextToItsStemIsRecorded() {
        Block fruit = world.getBlockAt(2, 64, 0);
        grow(fruit, Material.MELON.createBlockData());

        StoredEvent event = Recorded.only(Action.BLOCK_GROW);
        assertEquals("minecraft:melon", event.subject());
        assertEquals(2, event.x());
    }

    @Test
    public void everyStageIsRecordedWithCropGrowthStages() {
        TestPlugins.writeConfig(plugin, "logging:\n  crop-growth-stages: true\n");
        TestPlugins.reload();
        grow(crop(0, 2), wheat(3));

        assertEquals(wheat(3).getAsString(), Recorded.only(Action.BLOCK_GROW).subject());
    }

    @Test
    public void aDisabledCategoryRecordsNoGrowth() {
        TestPlugins.writeConfig(plugin, "logging:\n  crop-growth: false\n");
        TestPlugins.reload();
        grow(crop(0, 6), wheat(7));

        assertTrue(Recorded.events(Action.BLOCK_GROW).isEmpty());
    }

    @Test
    public void boneMealIsAChangeByItsPlayerAndFlowersAPlace() {
        Block crop = crop(0, 1);
        Block air = world.getBlockAt(1, 64, 0);
        fertilize(player, crop, grown(crop, wheat(4)), grown(air, Material.POPPY.createBlockData()));

        StoredEvent change = Recorded.only(Action.BLOCK_CHANGE);
        assertEquals("Alex", change.actor().name());
        assertEquals(wheat(4).getAsString(), change.subject());
        assertEquals(wheat(1).getAsString(), change.secondary());
        StoredEvent place = Recorded.only(Action.BLOCK_PLACE);
        assertEquals("Alex", place.actor().name());
        assertEquals("minecraft:poppy", place.subject());
    }

    @Test
    public void boneMealFromADispenserIsTheDispensers() {
        Block crop = crop(0, 1);
        fertilize(null, crop, grown(crop, wheat(3)));

        assertEquals("#dispenser", Recorded.only(Action.BLOCK_CHANGE).actor().name());
    }

    @Test
    public void aTreeGrownByBoneMealIsRecordedOnce() {
        Block sapling = world.getBlockAt(0, 64, 0);
        sapling.setType(Material.OAK_SAPLING);
        Block above = world.getBlockAt(0, 65, 0);
        List<BlockState> tree = List.of(grown(sapling, Material.OAK_LOG.createBlockData()),
                grown(above, Material.OAK_LOG.createBlockData()));
        server.getPluginManager().callEvent(new StructureGrowEvent(new Location(world, 0, 64, 0), TreeType.TREE, true,
                player, tree));
        fertilize(player, sapling, tree.toArray(BlockState[]::new));

        List<StoredEvent> events = Recorded.events(Action.BLOCK_CHANGE, Action.BLOCK_PLACE);
        assertEquals(2, events.size(), events.toString());
        assertTrue(events.stream().allMatch(event -> event.actor().name().equals("Alex")));
    }

    private void grow(Block block, BlockData into) {
        server.getPluginManager().callEvent(new BlockGrowEvent(block, grown(block, into)));
    }

    private void fertilize(PlayerMock by, Block block, BlockState... grown) {
        server.getPluginManager().callEvent(new BlockFertilizeEvent(block, by, List.of(grown)));
    }

    private static BlockState grown(Block block, BlockData into) {
        BlockState state = block.getState();
        state.setBlockData(into);
        return state;
    }

    private Block crop(int x, int age) {
        Block block = world.getBlockAt(x, 64, 0);
        block.setBlockData(wheat(age));
        return block;
    }

    private static Ageable wheat(int age) {
        Ageable wheat = (Ageable) Material.WHEAT.createBlockData();
        wheat.setAge(age);
        return wheat;
    }
}
