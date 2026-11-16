package gg.moonrise.hawkeye.entity.payload;

import java.util.List;
import org.jetbrains.annotations.NotNull;

/**
 * The text of both sides of a sign, one entry per line, each line an Adventure component in JSON
 * (so colours and formatting survive a rollback).
 *
 * @param front the front lines
 * @param back  the back lines
 */
public record SignSides(@NotNull List<String> front, @NotNull List<String> back) {

    /** No text on either side. */
    public static final SignSides EMPTY = new SignSides(List.of(), List.of());

    /**
     * Copies the line lists.
     *
     * @param front the front lines
     * @param back  the back lines
     */
    public SignSides {
        front = List.copyOf(front);
        back = List.copyOf(back);
    }

    /**
     * Whether both sides are empty.
     *
     * @return {@code true} when no line is stored
     */
    public boolean isEmpty() {
        return front.isEmpty() && back.isEmpty();
    }
}
