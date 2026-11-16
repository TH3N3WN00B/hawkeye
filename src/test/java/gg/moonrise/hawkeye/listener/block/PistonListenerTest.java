package gg.moonrise.hawkeye.listener.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.entity.payload.Payload;
import gg.moonrise.hawkeye.listener.Recorded;
import gg.moonrise.hawkeye.support.TestPlugins;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.PistonMoveReaction;
import org.bukkit.block.Sign;
import org.bukkit.block.data.type.Piston;
import org.bukkit.block.sign.Side;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.block.BlockMock;

public class PistonListenerTest {

    private ServerMock server;
    private World world;
    private Block piston;

    @BeforeEach
    public void setUp() {
        server = MockBukkit.mock();
        TestPlugins.load();
        world = server.addSimpleWorld("world");
        piston = world.getBlockAt(0, 64, 0);
        Piston data = (Piston) Material.STICKY_PISTON.createBlockData();
        data.setFacing(BlockFace.EAST);
        piston.setBlockData(data);
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void anExtendingPistonMovesEachBlockOneStepFarthestFirst() {
        Block stone = block(1, Material.STONE, PistonMoveReaction.MOVE);
        Block dirt = block(2, Material.DIRT, PistonMoveReaction.MOVE);
        server.getPluginManager().callEvent(new BlockPistonExtendEvent(piston, List.of(stone, dirt), BlockFace.EAST));

        List<StoredEvent> events = Recorded.events(Action.BLOCK_BREAK, Action.BLOCK_PLACE);
        assertEquals(4, events.size());
        assertTrue(events.stream().allMatch(event -> event.actor().name().equals("#piston")));
        assertMove(events.get(0), Action.BLOCK_BREAK, 2, "minecraft:dirt", "minecraft:air");
        assertMove(events.get(1), Action.BLOCK_PLACE, 3, "minecraft:dirt", "minecraft:air");
        assertMove(events.get(2), Action.BLOCK_BREAK, 1, "minecraft:stone", "minecraft:air");
        assertMove(events.get(3), Action.BLOCK_PLACE, 2, "minecraft:stone", "minecraft:air");
    }

    @Test
    public void aRetractingPistonPullsTowardsItself() {
        Block stone = block(2, Material.STONE, PistonMoveReaction.MOVE);
        server.getPluginManager().callEvent(new BlockPistonRetractEvent(piston, List.of(stone), BlockFace.EAST));

        List<StoredEvent> events = Recorded.events(Action.BLOCK_BREAK, Action.BLOCK_PLACE);
        assertEquals(2, events.size());
        assertMove(events.get(0), Action.BLOCK_BREAK, 2, "minecraft:stone", "minecraft:air");
        assertMove(events.get(1), Action.BLOCK_PLACE, 1, "minecraft:stone", "minecraft:air");
    }

    @Test
    public void aTorchInFrontOfAPushedBlockIsBrokenNotMoved() {
        Block stone = block(1, Material.STONE, PistonMoveReaction.MOVE);
        Block torch = block(2, Material.TORCH, PistonMoveReaction.BREAK);
        server.getPluginManager().callEvent(new BlockPistonExtendEvent(piston, List.of(stone, torch), BlockFace.EAST));

        List<StoredEvent> events = Recorded.events(Action.BLOCK_BREAK, Action.BLOCK_PLACE);
        assertEquals(3, events.size(), events.toString());
        assertTrue(events.stream().allMatch(event -> event.actor().name().equals("#piston")));
        assertMove(events.get(0), Action.BLOCK_BREAK, 2, "minecraft:torch", "minecraft:air");
        assertMove(events.get(1), Action.BLOCK_BREAK, 1, "minecraft:stone", "minecraft:air");
        assertMove(events.get(2), Action.BLOCK_PLACE, 2, "minecraft:stone", "minecraft:air");
        assertTrue(events.stream().noneMatch(event -> event.x() == 3), "no torch lands beyond the stone");
    }

    @Test
    public void aDestroyedBlockKeepsItsContentsAndARetractDestroysToo() {
        Block stone = block(2, Material.STONE, PistonMoveReaction.MOVE);
        Block sign = block(1, Material.OAK_SIGN, PistonMoveReaction.BREAK);
        ((Sign) sign.getState()).getSide(Side.FRONT).line(0, Component.text("Hi"));
        server.getPluginManager().callEvent(new BlockPistonRetractEvent(piston, List.of(stone, sign), BlockFace.EAST));

        List<StoredEvent> events = Recorded.events(Action.BLOCK_BREAK, Action.BLOCK_PLACE);
        assertEquals(3, events.size(), events.toString());
        assertMove(events.get(0), Action.BLOCK_BREAK, 1, sign.getBlockData().getAsString(), "minecraft:air");
        assertTrue(events.get(0).payload().orElseThrow() instanceof Payload.Sign);
        assertMove(events.get(2), Action.BLOCK_PLACE, 1, "minecraft:stone", "minecraft:air");
    }

    /**
     * A block at a position of the world that reports how a piston treats it; MockBukkit's own blocks
     * do not implement {@link Block#getPistonMoveReaction()}.
     */
    private Block block(int x, Material material, PistonMoveReaction reaction) {
        Location location = new Location(world, x, 64, 0);
        world.getBlockAt(location).setType(material);
        return new BlockMock(material, location) {
            @Override
            public PistonMoveReaction getPistonMoveReaction() {
                return reaction;
            }
        };
    }

    private static void assertMove(StoredEvent event, Action action, int x, String subject, String secondary) {
        assertEquals(action, event.action());
        assertEquals(x, event.x());
        assertEquals(subject, event.subject());
        assertEquals(secondary, event.secondary());
    }
}
