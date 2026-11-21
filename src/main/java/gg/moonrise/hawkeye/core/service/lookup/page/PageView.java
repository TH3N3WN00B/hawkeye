package gg.moonrise.hawkeye.core.service.lookup.page;

import gg.moonrise.hawkeye.core.service.lookup.LookupTotal;
import gg.moonrise.hawkeye.core.service.lookup.LookupWarning;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.entity.query.LookupQuery;
import java.util.List;
import java.util.OptionalInt;
import org.jetbrains.annotations.NotNull;

/**
 * One page of a lookup, ready to show.
 *
 * @param query    the query (an exact block is an inspector page)
 * @param page     the page number, from 1
 * @param pageSize events per page
 * @param total    how many events matched: exact, a lower bound or unknown
 * @param events   the page's events, newest first
 * @param hasMore  whether a next page exists
 * @param warnings shards that could not be read
 */
public record PageView(@NotNull LookupQuery query, int page, int pageSize, @NotNull LookupTotal total,
        @NotNull List<StoredEvent> events, boolean hasMore, @NotNull List<LookupWarning> warnings) {

    /**
     * Copies the lists.
     *
     * @param query    the query
     * @param page     the page number
     * @param pageSize events per page
     * @param total    the total
     * @param events   the events
     * @param hasMore  whether a next page exists
     * @param warnings unreadable shards
     */
    public PageView {
        events = List.copyOf(events);
        warnings = List.copyOf(warnings);
    }

    /**
     * How many pages there are, when the total is exact.
     *
     * @return the page count, or empty when the total is a lower bound or unknown
     */
    public @NotNull OptionalInt pages() {
        return total instanceof LookupTotal.Exact exact ? OptionalInt.of(pagesFor(exact.events()))
                : OptionalInt.empty();
    }

    /**
     * The pages a number of events fill, but never fewer than this page (and the next, when there is
     * one): the page count of an exact total, or the lower bound of the page count of a lower bound.
     *
     * @param events the events
     * @return at least this page's number
     */
    public int pagesFor(long events) {
        return Math.max(pagesOf(events, pageSize), hasMore ? page + 1 : page);
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
