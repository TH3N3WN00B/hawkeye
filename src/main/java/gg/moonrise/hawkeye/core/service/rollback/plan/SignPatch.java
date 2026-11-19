package gg.moonrise.hawkeye.core.service.rollback.plan;

import gg.moonrise.hawkeye.entity.payload.SignSides;
import org.jetbrains.annotations.NotNull;

/**
 * The sign text a change writes. Only the flagged sides are written, each line from {@code sides}
 * (Adventure JSON) and missing lines blank; an unflagged side keeps its text.
 *
 * @param sides the text
 * @param front whether the front is written
 * @param back  whether the back is written
 */
public record SignPatch(@NotNull SignSides sides, boolean front, boolean back) {

    /** Writes nothing. */
    public static final SignPatch NONE = new SignPatch(SignSides.EMPTY, false, false);

    /**
     * Writes both sides.
     *
     * @param sides the text
     * @return the patch
     */
    public static @NotNull SignPatch full(@NotNull SignSides sides) {
        return new SignPatch(sides, true, true);
    }

    /**
     * Whether nothing is written.
     *
     * @return {@code true} when neither side is flagged
     */
    public boolean isEmpty() {
        return !front && !back;
    }

    /**
     * This patch with the sides another one writes replaced by its text.
     *
     * @param other the patch that wins where it writes
     * @return the combined patch
     */
    public @NotNull SignPatch overriddenBy(@NotNull SignPatch other) {
        SignSides merged = new SignSides(other.front ? other.sides.front() : sides.front(),
                other.back ? other.sides.back() : sides.back());
        return new SignPatch(merged, front || other.front, back || other.back);
    }
}
