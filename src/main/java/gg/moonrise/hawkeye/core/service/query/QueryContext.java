package gg.moonrise.hawkeye.core.service.query;

import java.util.function.Predicate;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * What {@link QueryBuilder} needs to know about the sender and the command.
 *
 * @param position       where the sender stands, or {@code null} for the console
 * @param networkAllowed whether the sender may use {@code s:}
 * @param defaultRadius  the radius when {@code r:} is missing; 0 means every world
 * @param maxRadius      the largest {@code r:} the sender may use; 0 means no limit
 * @param worldExists    whether this server has a world of a name ({@code r:#world})
 */
public record QueryContext(
        @Nullable SenderPosition position,
        boolean networkAllowed,
        int defaultRadius,
        int maxRadius,
        @NotNull Predicate<String> worldExists) {

    /**
     * A block position of the sender.
     *
     * @param world the world name
     * @param x     block x
     * @param y     block y
     * @param z     block z
     */
    public record SenderPosition(@NotNull String world, int x, int y, int z) {
    }
}
