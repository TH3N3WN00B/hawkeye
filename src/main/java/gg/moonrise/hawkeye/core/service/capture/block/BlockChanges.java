package gg.moonrise.hawkeye.core.service.capture.block;

import gg.moonrise.hawkeye.core.service.capture.EventRecorder;
import gg.moonrise.hawkeye.entity.action.Action;
import java.util.Optional;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.jetbrains.annotations.NotNull;

/**
 * Classifies block changes that have no action of their own (entities, nature, buckets): a change to
 * air, or to the water a waterlogged or underwater block leaves behind, is a break; a change from air
 * or a replaceable block (water, lava, short grass, snow, ...) is a place; anything else is a change in
 * place.
 */
public final class BlockChanges {

    /** The state a broken waterlogged or underwater block leaves behind. */
    public static final String WATER = "minecraft:water[level=0]";

    private BlockChanges() {
    }

    /**
     * Classifies a change.
     *
     * @param before the state before
     * @param after  the state after
     * @return the change to record, or empty when nothing changed (or air became other air)
     */
    public static @NotNull Optional<BlockChange> classify(@NotNull BlockData before, @NotNull BlockData after) {
        Material from = before.getMaterial();
        Material to = after.getMaterial();
        if (before.equals(after) || (from.isAir() && to.isAir())) {
            return Optional.empty();
        }
        if (to.isAir() || (to == Material.WATER && WATER.equals(remains(before)))) {
            return Optional.of(new BlockChange(Action.BLOCK_BREAK, before, after));
        }
        if (isEmpty(from)) {
            return Optional.of(new BlockChange(Action.BLOCK_PLACE, after, before));
        }
        return Optional.of(new BlockChange(Action.BLOCK_CHANGE, after, before));
    }

    /**
     * Whether a block counts as empty space that a placement fills.
     *
     * @param material the block material
     * @return {@code true} for air and the {@code minecraft:replaceable} blocks
     */
    public static boolean isEmpty(@NotNull Material material) {
        return material.isAir() || Tag.REPLACEABLE.isTagged(material);
    }

    /**
     * What a block leaves behind when it is removed.
     *
     * @param broken the removed state
     * @return water for a waterlogged or underwater block, air otherwise
     */
    public static @NotNull String remains(@NotNull BlockData broken) {
        if (broken instanceof Waterlogged waterlogged && waterlogged.isWaterlogged()) {
            return WATER;
        }
        return switch (broken.getMaterial()) {
            case SEAGRASS, TALL_SEAGRASS, KELP, KELP_PLANT, BUBBLE_COLUMN -> WATER;
            default -> EventRecorder.AIR;
        };
    }

    /**
     * Whether a block is fire.
     *
     * @param material the material
     * @return {@code true} for fire and soul fire
     */
    public static boolean isFire(@NotNull Material material) {
        return material == Material.FIRE || material == Material.SOUL_FIRE;
    }

    /**
     * Whether a form event is water and lava meeting (cobblestone, stone or obsidian replacing a fluid).
     *
     * @param before the block that changes
     * @param after  the block it becomes
     * @return {@code true} for a fluid reaction
     */
    public static boolean isFluidForm(@NotNull Material before, @NotNull Material after) {
        return (before == Material.WATER || before == Material.LAVA)
                && (after == Material.COBBLESTONE || after == Material.STONE || after == Material.OBSIDIAN);
    }
}
