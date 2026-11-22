package gg.moonrise.hawkeye.listener.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.core.service.capture.EventRecorder;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.listener.Recorded;
import gg.moonrise.hawkeye.support.TestHawkeyePlugin;
import gg.moonrise.hawkeye.support.TestPlugins;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.SpongeAbsorbEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.block.state.BlockStateMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

public class FluidListenerTest {

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
    public void flowingWaterIsAPlaceOfFlowingWaterByWater() {
        Block source = block(0, Material.WATER);
        Block torch = block(1, Material.TORCH);
        server.getPluginManager().callEvent(new BlockFromToEvent(source, torch));

        StoredEvent event = Recorded.only(Action.BLOCK_PLACE);
        assertEquals("#water", event.actor().name());
        assertEquals(level(Material.WATER, 1), event.subject(), "a flow never records a source");
        assertEquals("minecraft:torch", event.secondary());
        assertEquals(1, event.x());
    }

    @Test
    public void aFlowDownIsFallingAndLavaDropsTwoLevels() {
        Block source = block(0, Material.WATER);
        server.getPluginManager().callEvent(new BlockFromToEvent(source, source.getRelative(BlockFace.DOWN)));
        server.getPluginManager().callEvent(new BlockFromToEvent(block(5, Material.LAVA), block(6, Material.AIR)));

        List<StoredEvent> places = Recorded.events(Action.BLOCK_PLACE);
        assertEquals(level(Material.WATER, 8), places.get(0).subject());
        assertEquals(63, places.get(0).y());
        assertEquals("#lava", places.get(1).actor().name());
        assertEquals(level(Material.LAVA, 2), places.get(1).subject());
    }

    @Test
    public void flowWithinTheSameFluidOrOfANonFluidIsNotRecorded() {
        server.getPluginManager().callEvent(new BlockFromToEvent(block(0, Material.WATER), block(1, Material.WATER)));
        server.getPluginManager().callEvent(new BlockFromToEvent(block(2, Material.STONE), block(3, Material.AIR)));

        assertTrue(Recorded.events(Action.BLOCK_PLACE).isEmpty());
    }

    @Test
    public void pouredWaterFlowsForThePlayerWhoPouredItAndPassesThemOn() {
        Block poured = block(0, Material.AIR);
        server.getPluginManager().callEvent(new PlayerBucketEmptyEvent(player, poured,
                poured.getRelative(BlockFace.DOWN), BlockFace.UP, Material.WATER_BUCKET,
                new ItemStack(Material.WATER_BUCKET), EquipmentSlot.HAND));
        poured.setType(Material.WATER);
        Block first = block(1, Material.AIR);
        server.getPluginManager().callEvent(new BlockFromToEvent(poured, first));
        first.setBlockData(Material.WATER.createBlockData());
        server.getPluginManager().callEvent(new BlockFromToEvent(first, block(2, Material.AIR)));

        List<StoredEvent> flows = Recorded.events(Action.BLOCK_PLACE).stream().filter(event -> event.x() > 0)
                .toList();
        assertEquals(2, flows.size());
        assertTrue(flows.stream().allMatch(event -> event.actor().name().equals("Alex")), flows.toString());
    }

    @Test
    public void withoutLiquidTrackingAFlowIsTheFluids() {
        TestPlugins.writeConfig(plugin, "logging:\n  liquid-tracking: false\n");
        TestPlugins.reload();
        Block poured = block(0, Material.AIR);
        server.getPluginManager().callEvent(new PlayerBucketEmptyEvent(player, poured,
                poured.getRelative(BlockFace.DOWN), BlockFace.UP, Material.WATER_BUCKET,
                new ItemStack(Material.WATER_BUCKET), EquipmentSlot.HAND));
        poured.setType(Material.WATER);
        server.getPluginManager().callEvent(new BlockFromToEvent(poured, block(1, Material.AIR)));

        StoredEvent flow = Recorded.events(Action.BLOCK_PLACE).stream().filter(event -> event.x() == 1)
                .findFirst().orElseThrow();
        assertEquals("#water", flow.actor().name());
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

    @Test
    public void lavaTurnedToBasaltIsAChangeByLavaAndNotNatural() {
        Block lava = block(0, Material.LAVA);
        BlockData before = lava.getBlockData();
        server.getPluginManager().callEvent(new BlockFormEvent(lava, new BlockStateMock(Material.BASALT)));

        StoredEvent event = Recorded.only(Action.BLOCK_CHANGE);
        assertEquals("#lava", event.actor().name());
        assertEquals("minecraft:basalt[axis=y]", event.subject());
        assertEquals(before.getAsString(), event.secondary());
        assertTrue(Recorded.events(Action.BLOCK_PLACE).isEmpty(), "the natural listener leaves fluid forms alone");
    }

    @Test
    public void basaltFromPouredLavaCountsForThePlayer() {
        Block poured = block(0, Material.AIR);
        server.getPluginManager().callEvent(new PlayerBucketEmptyEvent(player, poured,
                poured.getRelative(BlockFace.DOWN), BlockFace.UP, Material.LAVA_BUCKET,
                new ItemStack(Material.LAVA_BUCKET), EquipmentSlot.HAND));
        poured.setType(Material.LAVA);
        server.getPluginManager().callEvent(new BlockFormEvent(poured, new BlockStateMock(Material.BASALT)));

        assertEquals("Alex", Recorded.only(Action.BLOCK_CHANGE).actor().name());
    }

    @Test
    public void obsidianFromPouredWaterCountsForThePlayer() {
        Block poured = block(1, Material.AIR);
        server.getPluginManager().callEvent(new PlayerBucketEmptyEvent(player, poured,
                poured.getRelative(BlockFace.DOWN), BlockFace.UP, Material.WATER_BUCKET,
                new ItemStack(Material.WATER_BUCKET), EquipmentSlot.HAND));
        poured.setType(Material.WATER);
        server.getPluginManager().callEvent(new BlockFormEvent(block(0, Material.LAVA),
                new BlockStateMock(Material.OBSIDIAN)));

        assertEquals("Alex", Recorded.only(Action.BLOCK_CHANGE).actor().name());
    }

    @Test
    public void aPlacedSpongeSoaksUpWaterForItsPlayer() {
        Block sponge = block(0, Material.AIR);
        BlockState replaced = sponge.getState();
        sponge.setType(Material.SPONGE);
        server.getPluginManager().callEvent(new BlockPlaceEvent(sponge, replaced, sponge.getRelative(BlockFace.DOWN),
                new ItemStack(Material.SPONGE), player, true, EquipmentSlot.HAND));
        Block water = block(1, Material.WATER);
        String waterState = water.getBlockData().getAsString();
        Block stairs = world.getBlockAt(2, 64, 0);
        Stairs wet = (Stairs) Material.OAK_STAIRS.createBlockData();
        wet.setWaterlogged(true);
        stairs.setBlockData(wet);
        absorb(sponge, water, stairs);

        StoredEvent broken = Recorded.only(Action.BLOCK_BREAK);
        assertEquals("Alex", broken.actor().name());
        assertEquals(waterState, broken.subject());
        assertEquals(EventRecorder.AIR, broken.secondary());
        StoredEvent drained = Recorded.only(Action.BLOCK_CHANGE);
        assertEquals("Alex", drained.actor().name());
        assertEquals(wet.getAsString(), drained.secondary());
        assertTrue(drained.subject().contains("waterlogged=false"), drained.subject());
    }

    @Test
    public void aSpongeNobodyPlacedIsTheSponge() {
        absorb(block(0, Material.SPONGE), block(1, Material.WATER));

        assertEquals("#sponge", Recorded.only(Action.BLOCK_BREAK).actor().name());
    }

    private void absorb(Block sponge, Block... wet) {
        List<BlockState> drained = new ArrayList<>();
        for (Block block : wet) {
            BlockState state = block.getState();
            if (block.getBlockData() instanceof Stairs stairs) {
                Stairs dry = (Stairs) stairs.clone();
                dry.setWaterlogged(false);
                state.setBlockData(dry);
            } else {
                state.setType(Material.AIR);
            }
            drained.add(state);
        }
        server.getPluginManager().callEvent(new SpongeAbsorbEvent(sponge, drained));
    }

    private Block block(int x, Material material) {
        Block block = world.getBlockAt(x, 64, 0);
        block.setType(material);
        return block;
    }

    private static String level(Material fluid, int level) {
        Levelled state = (Levelled) fluid.createBlockData();
        state.setLevel(level);
        return state.getAsString();
    }
}
