package gg.moonrise.hawkeye.core.service.capture.block;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.jetbrains.annotations.NotNull;

/**
 * Turns the blocks a piston moves into an ordered list of single moves that replays the push one
 * block at a time, farthest block first. Each move's {@code replaced} is what its destination holds
 * at that point of the replay (air where a block already moved away or was destroyed), so recording a
 * break at {@code from} and a place at {@code to} per move undoes exactly, newest first.
 *
 * <p>The server reports the blocks a piston destroys (torches, flowers, sugar cane in the way) in the
 * same list as the blocks it moves, and destroys them before anything moves; {@link #push} separates
 * them so they are recorded as broken rather than as moved one block on.
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
     * What a piston does, in replay order: the destroyed blocks go first, then the moves.
     *
     * @param destroyed the blocks the piston destroys, still in the world
     * @param moves     the moves of the other blocks, farthest first
     */
    public record Push(@NotNull List<Block> destroyed, @NotNull List<Move> moves) {
    }

    /**
     * Orders a piston's moved blocks.
     *
     * @param blocks    the blocks the piston moves, still at their old positions
     * @param direction the direction they move in
     * @return one move per block, farthest along {@code direction} first
     */
    public static @NotNull List<Move> plan(@NotNull List<Block> blocks, @NotNull BlockFace direction) {
        return moves(blocks, direction, Set.of());
    }

    /**
     * Separates the blocks a piston destroys from the ones it moves, and orders the moves.
     *
     * @param blocks    every block the piston event reports, still at its old position
     * @param direction the direction the moved blocks go
     * @param destroyed which of them the piston destroys instead of moving
     * @return the destroyed blocks and the moves of the rest
     */
    public static @NotNull Push push(@NotNull List<Block> blocks, @NotNull BlockFace direction,
            @NotNull Predicate<Block> destroyed) {
        List<Block> broken = new ArrayList<>();
        List<Block> moved = new ArrayList<>(blocks.size());
        Set<Position> cleared = new HashSet<>();
        for (Block block : blocks) {
            if (destroyed.test(block)) {
                broken.add(block);
                cleared.add(Position.of(block));
            } else {
                moved.add(block);
            }
        }
        return new Push(List.copyOf(broken), moves(moved, direction, cleared));
    }

    private static @NotNull List<Move> moves(@NotNull List<Block> blocks, @NotNull BlockFace direction,
            @NotNull Set<Position> cleared) {
        if (blocks.isEmpty()) {
            return List.of();
        }
        List<Block> ordered = new ArrayList<>(blocks);
        ordered.sort(Comparator.comparingLong((Block block) -> distance(block, direction)).reversed());
        BlockData air = Material.AIR.createBlockData();
        Set<Position> vacated = new HashSet<>(cleared);
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
