package gg.moonrise.hawkeye.core.service.capture.block;

import java.util.Optional;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.Waterlogged;
import org.jetbrains.annotations.NotNull;

/**
 * The state a fluid leaves in the block it flows into. A flow is never a new source: flowing down it is
 * a falling fluid (level 8), sideways it is one drop-off weaker than the block it came from (1 for
 * water, 2 for lava, 1 for lava where it flows fast, as in the nether). Falling fluid spreads like a
 * source. Levels are Minecraft's: 0 a source, 1 to 7 flowing, 8 and up falling.
 */
public final class FluidFlows {

    /** The level of a falling fluid. */
    public static final int FALLING = 8;

    /** The weakest flowing level. */
    public static final int WEAKEST = 7;

    private FluidFlows() {
    }

    /**
     * The state a flow leaves behind.
     *
     * @param from     the block the fluid flows from (water, lava, or a waterlogged block)
     * @param down     whether it flows down
     * @param fastLava whether lava flows fast in this world
     * @return the flowing state, or empty when {@code from} holds no fluid (a dragon egg teleporting)
     */
    public static @NotNull Optional<BlockData> flowing(@NotNull BlockData from, boolean down, boolean fastLava) {
        Material fluid;
        int level;
        if ((from.getMaterial() == Material.WATER || from.getMaterial() == Material.LAVA)
                && from instanceof Levelled levelled) {
            fluid = from.getMaterial();
            level = levelled.getLevel();
        } else if (from instanceof Waterlogged waterlogged && waterlogged.isWaterlogged()) {
            // A waterlogged block spreads like a water source.
            fluid = Material.WATER;
            level = 0;
        } else {
            return Optional.empty();
        }
        Levelled state = (Levelled) fluid.createBlockData();
        state.setLevel(down ? FALLING : Math.min(WEAKEST, (level >= FALLING ? 0 : level) + dropOff(fluid, fastLava)));
        return Optional.of(state);
    }

    private static int dropOff(@NotNull Material fluid, boolean fastLava) {
        return fluid == Material.LAVA && !fastLava ? 2 : 1;
    }
}
