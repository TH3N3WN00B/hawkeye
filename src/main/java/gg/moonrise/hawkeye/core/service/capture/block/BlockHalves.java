package gg.moonrise.hawkeye.core.service.capture.block;

import java.util.Optional;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.block.data.type.TrapDoor;
import org.jetbrains.annotations.NotNull;

/**
 * Finds the other half of a two-block block (doors, tall plants, beds). Breaking one half removes the
 * other through physics without an event, so the listener records both and a rollback restores both.
 */
public final class BlockHalves {

    private BlockHalves() {
    }

    /**
     * The other half of a two-block block.
     *
     * @param block one half, still in the world
     * @return the other half when it is the same block type, or empty for a single block
     */
    public static @NotNull Optional<Block> otherHalf(@NotNull Block block) {
        BlockData data = block.getBlockData();
        Optional<Block> other = switch (data) {
            // Stairs and trapdoors have a top/bottom half but are single blocks.
            case Stairs _, TrapDoor _ -> Optional.empty();
            case Bed bed -> Optional.of(block.getRelative(bed.getPart() == Bed.Part.FOOT ? bed.getFacing()
                    : bed.getFacing().getOppositeFace()));
            case Bisected bisected -> Optional.of(block.getRelative(bisected.getHalf() == Bisected.Half.BOTTOM
                    ? BlockFace.UP : BlockFace.DOWN));
            default -> Optional.empty();
        };
        return other.filter(half -> half.getType() == data.getMaterial());
    }
}
