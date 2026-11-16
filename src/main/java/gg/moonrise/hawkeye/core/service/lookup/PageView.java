package gg.moonrise.hawkeye.core.service.lookup;

import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.entity.query.LookupQuery;
import java.util.List;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.jetbrains.annotations.NotNull;

/**
 * One page of a lookup, ready to show.
 *
 * @param query    the query (an exact block is an inspector page)
 * @param page     the page number, from 1
 * @param pageSize events per page
 * @param total    how many events matched, or {@link LookupSession#UNKNOWN_TOTAL}
 * @param events   the page's events, newest first
 * @param hasMore  whether a next page exists
 * @param warnings shards that could not be read
 */
public record PageView(@NotNull LookupQuery query, int page, int pageSize, long total,
        @NotNull List<StoredEvent> events, boolean hasMore, @NotNull List<LookupWarning> warnings) {

    /**
     * Copies the lists.
     *
     * @param query    the query
     * @param page     the page number
     * @param pageSize events per page
     * @param total    the total or {@link LookupSession#UNKNOWN_TOTAL}
     * @param events   the events
     * @param hasMore  whether a next page exists
     * @param warnings unreadable shards
     */
    public PageView {
        events = List.copyOf(events);
        warnings = List.copyOf(warnings);
    }

    /**
     * The total, if it could be counted.
     *
     * @return the total, or empty
     */
    public @NotNull OptionalLong knownTotal() {
        return total < 0 ? OptionalLong.empty() : OptionalLong.of(total);
    }

    /**
     * How many pages there are: from the total, but never fewer than this page (and the next, when
     * there is one).
     *
     * @return the page count, or empty when the total is unknown
     */
    public @NotNull OptionalInt pages() {
        if (total < 0) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(Math.max(pagesOf(total, pageSize), hasMore ? page + 1 : page));
    }

    /**
     * The pages {@code total} events fill.
     *
     * @param total    the event count
     * @param pageSize events per page
     * @return at least 1
     */
    public static int pagesOf(long total, int pageSize) {
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, (total + pageSize - 1) / pageSize));
    }
}
