package gg.moonrise.hawkeye.core.service.lookup.page;

import gg.moonrise.hawkeye.core.service.lookup.LookupTotal;
import gg.moonrise.hawkeye.entity.query.LookupQuery;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import org.jetbrains.annotations.NotNull;

/**
 * A sender's last lookup, kept so {@code /hawkeye page} can continue it. Pages are keyset pages, so
 * the session remembers where every page seen so far starts: page {@code n} starts at
 * {@code starts.get(n - 1)}, the exclusive upper id bound of its newest event. The first start is
 * pinned just above the newest event of the first page, so paging back to page 1 shows the same
 * events even after new ones were recorded.
 *
 * @param query    the query
 * @param pageSize events per page
 * @param total    how many events matched when the lookup ran: exact, a lower bound or unknown
 * @param starts   the start cursor of every page seen so far, page 1 first (never empty)
 */
public record LookupSession(@NotNull LookupQuery query, int pageSize, @NotNull LookupTotal total,
        @NotNull List<Long> starts) {

    /**
     * Validates and copies.
     *
     * @param query    the query
     * @param pageSize events per page
     * @param total    the total
     * @param starts   the known page starts
     */
    public LookupSession {
        if (pageSize < 1) {
            throw new IllegalArgumentException("the page size must be at least 1");
        }
        if (starts.isEmpty()) {
            throw new IllegalArgumentException("a session knows at least where page 1 starts");
        }
        starts = List.copyOf(starts);
    }

    /**
     * How many pages there are: from an exact total, but never fewer than the pages already seen. A
     * lower bound is no page count: the pages beyond it are found by walking, like those of an unknown
     * total.
     *
     * @return the page count, or empty when the total is not exact
     */
    public @NotNull OptionalInt pages() {
        return switch (total) {
            case LookupTotal.Exact exact -> OptionalInt.of(Math.max(PageView.pagesOf(exact.events(), pageSize),
                    starts.size()));
            case LookupTotal.AtLeast atLeast -> OptionalInt.empty();
            case LookupTotal.Unknown unknown -> OptionalInt.empty();
        };
    }

    /**
     * How many pages have a known start.
     *
     * @return at least 1
     */
    public int knownPages() {
        return starts.size();
    }

    /**
     * Where a known page starts.
     *
     * @param page 1 to {@link #knownPages()}
     * @return its cursor
     */
    public long start(int page) {
        return starts.get(page - 1);
    }

    /**
     * This session with another total.
     *
     * @param newTotal the total
     * @return the copy, or this session when the total is the same
     */
    public @NotNull LookupSession withTotal(@NotNull LookupTotal newTotal) {
        return newTotal.equals(total) ? this : new LookupSession(query, pageSize, newTotal, starts);
    }

    /**
     * This session with the start of the page after the last known one.
     *
     * @param page   the page number
     * @param cursor where it starts
     * @return the copy, or this session when the page is not the next unknown one
     */
    public @NotNull LookupSession withStart(int page, long cursor) {
        if (page != starts.size() + 1) {
            return this;
        }
        List<Long> more = new ArrayList<>(starts);
        more.add(cursor);
        return new LookupSession(query, pageSize, total, more);
    }
}
