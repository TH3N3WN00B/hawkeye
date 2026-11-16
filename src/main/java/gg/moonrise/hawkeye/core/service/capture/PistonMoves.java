package gg.moonrise.hawkeye.core.service.capture;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.jetbrains.annotations.NotNull;

/**
 * Turns the blocks a piston moves into an ordered list of single moves that replays the push one
 * block at a time, farthest block first. Each move's {@code replaced} is what its destination holds
 * at that point of the replay (air where a block already moved away), so recording a break at
 * {@code from} and a place at {@code to} per move undoes exactly, newest first.
 */
public final class PistonMoves {

    private PistonMoves() {
    }

    /**
     * One block moved by one step.
     *
     * @param from     where it was
     * @param to       where it goes
     * @param data     its state
     * @param replaced what {@code to} holds before the block arrives
     */
    public record Move(@NotNull Block from, @NotNull Block to, @NotNull BlockData data, @NotNull BlockData replaced) {
    }

    /**
     * Orders a piston's moved blocks.
     *
     * @param blocks    the blocks the piston moves, still at their old positions
     * @param direction the direction they move in
     * @return one move per block, farthest along {@code direction} first
     */
    public static @NotNull List<Move> plan(@NotNull List<Block> blocks, @NotNull BlockFace direction) {
        if (blocks.isEmpty()) {
            return List.of();
        }
        List<Block> ordered = new ArrayList<>(blocks);
        ordered.sort(Comparator.comparingLong((Block block) -> distance(block, direction)).reversed());
        BlockData air = Material.AIR.createBlockData();
        Set<Position> vacated = new HashSet<>();
        List<Move> moves = new ArrayList<>(ordered.size());
        for (Block from : ordered) {
            Block to = from.getRelative(direction);
            BlockData replaced = vacated.contains(Position.of(to)) ? air : to.getBlockData();
            moves.add(new Move(from, to, from.getBlockData(), replaced));
            vacated.add(Position.of(from));
        }
        return moves;
    }

    private static long distance(@NotNull Block block, @NotNull BlockFace direction) {
        return (long) block.getX() * direction.getModX() + (long) block.getY() * direction.getModY()
                + (long) block.getZ() * direction.getModZ();
    }

    /** A block position, as a set key that does not depend on how a {@link Block} implements equality. */
    private record Position(int x, int y, int z) {

        private static @NotNull Position of(@NotNull Block block) {
            return new Position(block.getX(), block.getY(), block.getZ());
        }
    }
}
