package gg.moonrise.hawkeye.listener.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.entity.payload.Payload;
import gg.moonrise.hawkeye.listener.Recorded;
import gg.moonrise.hawkeye.support.TestPlugins;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.ExplosionResult;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.RespawnAnchor;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

public class ExplosionListenerTest {

    private ServerMock server;
    private World world;
    private Location center;

    @BeforeEach
    public void setUp() {
        server = MockBukkit.mock();
        TestPlugins.load();
        world = server.addSimpleWorld("world");
        center = new Location(world, 0, 64, 0);
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void tntLitByAPlayerIsBlamedOnThePlayer() {
        PlayerMock player = server.addPlayer("Alex");
        TNTPrimed tnt = world.spawn(center, TNTPrimed.class);
        tnt.setSource(player);
        List<Block> destroyed = blocks(Material.STONE, Material.DIRT);
        server.getPluginManager().callEvent(new EntityExplodeEvent(tnt, center, destroyed, 1, ExplosionResult.DESTROY));

        List<StoredEvent> breaks = Recorded.events(Action.BLOCK_BREAK);
        assertEquals(2, breaks.size());
        assertTrue(breaks.stream().allMatch(event -> event.actor().name().equals("Alex")));
        assertEquals("minecraft:stone", breaks.get(0).subject());
        assertEquals("minecraft:dirt", breaks.get(1).subject());
    }

    @Test
    public void aCreeperIsASourceAndContainersKeepTheirItems() {
        Creeper creeper = world.spawn(center, Creeper.class);
        List<Block> destroyed = blocks(Material.BARREL);
        ((Barrel) destroyed.getFirst().getState()).getInventory().addItem(new ItemStack(Material.GOLD_INGOT, 3));
        server.getPluginManager().callEvent(new EntityExplodeEvent(creeper, center, destroyed, 1,
                ExplosionResult.DESTROY_WITH_DECAY));

        StoredEvent event = Recorded.only(Action.BLOCK_BREAK);
        assertEquals("#creeper", event.actor().name());
        assertTrue(event.payload().orElseThrow() instanceof Payload.Inventory);
    }

    @Test
    public void aBlockExplosionIsBlamedOnTheExplosion() {
        Block bed = world.getBlockAt(0, 64, 0);
        List<Block> destroyed = blocks(Material.COBBLESTONE);
        server.getPluginManager().callEvent(new BlockExplodeEvent(bed, bed.getState(), destroyed, 1,
                ExplosionResult.DESTROY));

        assertEquals("#explosion", Recorded.only(Action.BLOCK_BREAK).actor().name());
    }

    @Test
    public void aBedClickedInTheNetherCountsForThePlayerOnBothHalves() {
        WorldMock nether = server.addSimpleWorld("nether");
        nether.setEnvironment(World.Environment.NETHER);
        PlayerMock player = server.addPlayer("Alex");
        Block foot = nether.getBlockAt(0, 64, 0);
        foot.setBlockData(bed(Bed.Part.FOOT));
        Block head = nether.getBlockAt(1, 64, 0);
        head.setBlockData(bed(Bed.Part.HEAD));
        rightClick(player, foot);
        Block stone = nether.getBlockAt(3, 64, 0);
        stone.setType(Material.STONE);
        // The event carries the exploded block's state (on the server the block itself is already gone).
        BlockState exploded = head.getState();
        exploded.setBlockData(bed(Bed.Part.HEAD)); // MockBukkit's state of a setBlockData block reads air
        server.getPluginManager().callEvent(new BlockExplodeEvent(head, exploded,
                new ArrayList<>(List.of(stone)), 1, ExplosionResult.DESTROY));

        List<StoredEvent> breaks = Recorded.events(Action.BLOCK_BREAK);
        assertEquals(3, breaks.size(), breaks.toString());
        assertTrue(breaks.stream().allMatch(event -> event.actor().name().equals("Alex")), breaks.toString());
        assertTrue(breaks.stream().anyMatch(event -> event.subject().contains("part=foot") && event.x() == 0),
                breaks.toString());
        assertTrue(breaks.stream().anyMatch(event -> event.subject().contains("part=head") && event.x() == 1),
                breaks.toString());
    }

    @Test
    public void aChargedAnchorOutsideTheNetherCountsForThePlayer() {
        PlayerMock player = server.addPlayer("Alex");
        Block anchor = world.getBlockAt(0, 64, 0);
        RespawnAnchor charged = (RespawnAnchor) Material.RESPAWN_ANCHOR.createBlockData();
        charged.setCharges(2);
        anchor.setBlockData(charged);
        rightClick(player, anchor);
        BlockState exploded = anchor.getState();
        exploded.setBlockData(charged);
        server.getPluginManager().callEvent(new BlockExplodeEvent(anchor, exploded,
                blocks(Material.COBBLESTONE), 1, ExplosionResult.DESTROY));

        List<StoredEvent> breaks = Recorded.events(Action.BLOCK_BREAK);
        assertEquals(2, breaks.size(), breaks.toString());
        assertTrue(breaks.stream().allMatch(event -> event.actor().name().equals("Alex")), breaks.toString());
        assertTrue(breaks.stream().anyMatch(event -> event.subject().startsWith("minecraft:respawn_anchor")),
                breaks.toString());
    }

    @Test
    public void aBedInTheOverworldOrAnEmptyAnchorIsNotBlamedOnTheClicker() {
        PlayerMock player = server.addPlayer("Alex");
        Block bed = world.getBlockAt(0, 64, 0);
        bed.setBlockData(bed(Bed.Part.FOOT));
        rightClick(player, bed);
        Block anchor = world.getBlockAt(0, 64, 5);
        anchor.setType(Material.RESPAWN_ANCHOR);
        rightClick(player, anchor);
        BlockState bedState = bed.getState();
        bedState.setBlockData(bed(Bed.Part.FOOT));
        server.getPluginManager().callEvent(new BlockExplodeEvent(bed, bedState, blocks(Material.STONE), 1,
                ExplosionResult.DESTROY));
        BlockState anchorState = anchor.getState();
        server.getPluginManager().callEvent(new BlockExplodeEvent(anchor, anchorState, blocks(Material.DIRT),
                1, ExplosionResult.DESTROY));

        // Both bed halves, the anchor, and the stone and dirt each explosion destroyed.
        List<StoredEvent> breaks = Recorded.events(Action.BLOCK_BREAK);
        assertEquals(5, breaks.size(), breaks.toString());
        assertTrue(breaks.stream().allMatch(event -> event.actor().name().equals("#explosion")), breaks.toString());
    }

    @Test
    public void explosionsThatKeepOrTriggerBlocksRecordNothing() {
        Creeper creeper = world.spawn(center, Creeper.class);
        server.getPluginManager().callEvent(new EntityExplodeEvent(creeper, center, blocks(Material.STONE), 1,
                ExplosionResult.KEEP));
        server.getPluginManager().callEvent(new EntityExplodeEvent(creeper, center, blocks(Material.OAK_DOOR), 1,
                ExplosionResult.TRIGGER_BLOCK));

        assertTrue(Recorded.events(Action.BLOCK_BREAK).isEmpty());
    }

    private void rightClick(PlayerMock player, Block block) {
        server.getPluginManager().callEvent(new PlayerInteractEvent(player,
                org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK, null, block, BlockFace.UP, EquipmentSlot.HAND));
    }

    private static Bed bed(Bed.Part part) {
        Bed bed = (Bed) Material.RED_BED.createBlockData();
        bed.setFacing(BlockFace.EAST);
        bed.setPart(part);
        return bed;
    }

    private List<Block> blocks(Material... materials) {
        List<Block> blocks = new ArrayList<>();
        for (int i = 0; i < materials.length; i++) {
            Block block = world.getBlockAt(i + 1, 64, 0);
            block.setType(materials[i]);
            blocks.add(block);
        }
        return blocks;
    }
}
