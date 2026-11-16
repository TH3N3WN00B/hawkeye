package gg.moonrise.hawkeye.core.service.capture;

import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.jetbrains.annotations.NotNull;

/**
 * Decides which plant growth is worth an event. Growth is the busiest thing a world does, so by
 * default only its outcomes are kept: a new block (a melon or pumpkin, the next sugar cane or cactus
 * block, a vine, the next amethyst bud) and a crop reaching its last stage. The intermediate stages of
 * a crop are recorded only when {@code logging.crop-growth-stages} asks for them.
 */
public final class CropGrowth {

    private CropGrowth() {
    }

    /**
     * Whether a growth step is recorded.
     *
     * @param before the block before it grew
     * @param after  what it grows into
     * @param stages whether every intermediate stage is recorded
     * @return {@code true} for a new block, a ripe crop, or any change when stages are recorded
     */
    public static boolean recorded(@NotNull BlockData before, @NotNull BlockData after, boolean stages) {
        if (before.equals(after)) {
            return false;
        }
        if (stages || before.getMaterial() != after.getMaterial()) {
            return true;
        }
        return after instanceof Ageable ageable && ageable.getAge() >= ageable.getMaximumAge();
    }
}
