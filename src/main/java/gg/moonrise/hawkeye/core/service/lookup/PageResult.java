package gg.moonrise.hawkeye.core.service.lookup;

import org.jetbrains.annotations.NotNull;

/** Outcome of opening a lookup or turning to one of its pages ({@link LookupPager}). */
public sealed interface PageResult {

    /**
     * The page to show.
     *
     * @param view the page
     */
    record Shown(@NotNull PageView view) implements PageResult {
    }

    /** {@code /hawkeye page} without a recent lookup. */
    record NoSession() implements PageResult {
    }

    /**
     * The page is beyond the last one.
     *
     * @param pages how many pages there are
     */
    record OutOfRange(int pages) implements PageResult {
    }

    /**
     * The lookup could not run.
     *
     * @param problem why
     */
    record Unavailable(@NotNull LookupOutcome.Problem problem) implements PageResult {
    }
}
