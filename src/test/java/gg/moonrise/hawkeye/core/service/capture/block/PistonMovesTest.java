package gg.moonrise.hawkeye.core.service.capture.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

public class PistonMovesTest {

    private World world;

    @BeforeEach
    public void setUp() {
        ServerMock server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void theFarthestBlockMovesFirstAndLaterMovesReplaceAir() {
        Block near = block(1, Material.STONE);
        Block far = block(2, Material.DIRT);
        Block beyond = block(3, Material.SHORT_GRASS);

        List<PistonMoves.Move> moves = PistonMoves.plan(List.of(near, far), BlockFace.EAST);

        assertEquals(2, moves.size());
        PistonMoves.Move first = moves.get(0);
        assertSame(far, first.from());
        assertSame(beyond, first.to());
        assertEquals(Material.DIRT, first.data().getMaterial());
        assertEquals(Material.SHORT_GRASS, first.replaced().getMaterial());
        PistonMoves.Move second = moves.get(1);
        assertSame(near, second.from());
        assertSame(far, second.to());
        assertEquals(Material.STONE, second.data().getMaterial());
        assertTrue(second.replaced().getMaterial().isAir(), "the dirt already moved away");
    }

    @Test
    public void aPullOrdersByTheMovementDirection() {
        Block near = block(2, Material.STONE);
        Block far = block(3, Material.DIRT);

        List<PistonMoves.Move> moves = PistonMoves.plan(List.of(far, near), BlockFace.WEST);

        assertSame(near, moves.get(0).from());
        assertSame(far, moves.get(1).from());
        assertTrue(moves.get(1).replaced().getMaterial().isAir());
    }

    @Test
    public void nothingMovedIsNoMoves() {
        assertTrue(PistonMoves.plan(List.of(), BlockFace.UP).isEmpty());
    }

    @Test
    public void aDestroyedBlockIsNotMovedAndItsPlaceIsFreeForTheBlockBehind() {
        Block stone = block(1, Material.STONE);
        Block torch = block(2, Material.TORCH);

        PistonMoves.Push push = PistonMoves.push(List.of(stone, torch), BlockFace.EAST,
                block -> block.getType() == Material.TORCH);

        assertEquals(List.of(torch), push.destroyed());
        assertEquals(1, push.moves().size());
        PistonMoves.Move move = push.moves().getFirst();
        assertSame(stone, move.from());
        assertSame(torch, move.to());
        assertTrue(move.replaced().getMaterial().isAir(), "the torch is destroyed before the stone moves");
    }

    @Test
    public void aPushWithoutDestroyedBlocksIsThePlan() {
        Block near = block(1, Material.STONE);
        Block far = block(2, Material.DIRT);

        PistonMoves.Push push = PistonMoves.push(List.of(near, far), BlockFace.EAST, block -> false);

        assertTrue(push.destroyed().isEmpty());
        assertEquals(PistonMoves.plan(List.of(near, far), BlockFace.EAST), push.moves());
    }

    private Block block(int x, Material material) {
        Block block = world.getBlockAt(x, 64, 0);
        block.setType(material);
        return block;
    }
}
