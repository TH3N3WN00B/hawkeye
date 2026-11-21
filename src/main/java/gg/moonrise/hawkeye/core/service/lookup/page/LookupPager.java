package gg.moonrise.hawkeye.core.service.lookup.page;

import gg.moonrise.hawkeye.core.service.lookup.LookupOutcome;
import gg.moonrise.hawkeye.core.service.lookup.LookupService;
import gg.moonrise.hawkeye.core.service.lookup.LookupTotal;
import gg.moonrise.hawkeye.core.service.lookup.LookupWarning;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.entity.query.LookupQuery;
import gg.moonrise.moss.spring.SpringComponent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

/**
 * Opens lookups and turns their pages. Opening runs the first page and a bounded count in parallel and
 * starts the sender's session ({@link LookupSessions}): the count reads at most {@link #COUNT_SEGMENTS}
 * archive segments per shard, so a large result shows a lower bound ({@link LookupTotal.AtLeast})
 * rather than making the first page wait for the whole history. Turning to page {@code n} reads it
 * from its known start, or walks forward from the last known page when {@code n} is further, recording
 * every page start it passes; past a lower bound it keeps walking until the rows end, and once they do
 * the session's total becomes exact. A walk reads up to {@link #WALK_ROWS} rows per query, so a long
 * jump costs a few queries rather than one per page.
 */
@Slf4j
@SpringComponent
@RequiredArgsConstructor
public final class LookupPager {

    /** The most rows one walking query reads. */
    public static final int WALK_ROWS = 1_000;

    /** The most archive segment bodies the count of an opened lookup reads per shard. */
    public static final int COUNT_SEGMENTS = 16;

    private final @NotNull LookupService lookupService;
    private final @NotNull LookupSessions sessions;

    /**
     * Runs a new lookup and shows its first page; the sender's session starts over with it.
     *
     * @param sender   who looks it up
     * @param query    the query
     * @param pageSize events per page
     * @return the first page, or why there is none
     */
    public @NotNull CompletableFuture<PageResult> open(@NotNull CommandSender sender, @NotNull LookupQuery query,
            int pageSize) {
        CompletableFuture<LookupOutcome> count = lookupService.count(query, COUNT_SEGMENTS);
        return lookupService.page(query, 0, pageSize)
                .thenCombine(count, (page, total) -> opened(sender, query, pageSize, page, total))
                .exceptionally(LookupPager::failed);
    }

    /**
     * Shows a page of the sender's last lookup.
     *
     * @param sender who asks
     * @param number the page number; below 1 means page 1
     * @return the page, or why there is none
     */
    public @NotNull CompletableFuture<PageResult> page(@NotNull CommandSender sender, int number) {
        Optional<LookupSession> found = sessions.get(sender);
        if (found.isEmpty()) {
            return CompletableFuture.completedFuture(new PageResult.NoSession());
        }
        LookupSession session = found.get();
        int target = Math.max(1, number);
        OptionalInt pages = session.pages();
        if (pages.isPresent() && target > pages.getAsInt()) {
            return CompletableFuture.completedFuture(new PageResult.OutOfRange(pages.getAsInt()));
        }
        return walk(sender, session, Math.min(target, session.knownPages()), target)
                .exceptionally(LookupPager::failed);
    }

    private @NotNull PageResult opened(@NotNull CommandSender sender, @NotNull LookupQuery query, int pageSize,
            @NotNull LookupOutcome page, @NotNull LookupOutcome count) {
        return switch (page) {
            case LookupOutcome.Page first -> {
                LookupTotal total = firstTotal(LookupTotal.of(count), first);
                List<Long> starts = new ArrayList<>();
                starts.add(first.events().isEmpty() ? 0 : first.events().getFirst().id() + 1);
                if (first.hasMore()) {
                    starts.add(first.nextCursor());
                }
                sessions.put(sender, new LookupSession(query, pageSize, total, starts));
                List<LookupWarning> warnings = new ArrayList<>(first.warnings());
                if (count instanceof LookupOutcome.Count counted) {
                    warnings.addAll(counted.warnings());
                }
                yield new PageResult.Shown(new PageView(query, 1, pageSize, total, first.events(), first.hasMore(),
                        distinctShards(warnings)));
            }
            case LookupOutcome.Problem problem -> new PageResult.Unavailable(problem);
            case LookupOutcome.Count unexpected -> throw new IllegalStateException("a page lookup returned a count");
        };
    }

    /**
     * Reads from page {@code from} (whose start is known) up to page {@code target}, a batch of pages
     * per query.
     */
    private @NotNull CompletableFuture<PageResult> walk(@NotNull CommandSender sender, @NotNull LookupSession session,
            int from, int target) {
        int pageSize = session.pageSize();
        int batch = Math.min(target - from + 1, Math.max(1, WALK_ROWS / pageSize));
        return lookupService.page(session.query(), session.start(from), batch * pageSize).thenCompose(outcome ->
                switch (outcome) {
                    case LookupOutcome.Page rows -> walked(sender, session, from, target, rows);
                    case LookupOutcome.Problem problem ->
                            CompletableFuture.completedFuture(new PageResult.Unavailable(problem));
                    case LookupOutcome.Count unexpected ->
                            throw new IllegalStateException("a page lookup returned a count");
                });
    }

    private @NotNull CompletableFuture<PageResult> walked(@NotNull CommandSender sender, @NotNull LookupSession session,
            int from, int target, @NotNull LookupOutcome.Page rows) {
        int pageSize = session.pageSize();
        List<StoredEvent> events = rows.events();
        LookupSession updated = rows.hasMore() ? session
                : session.withTotal(ended(session.total(), false, (long) (from - 1) * pageSize + events.size()));
        // Page from + j starts below the last event of page from + j - 1.
        for (int j = 1; j * pageSize <= events.size(); j++) {
            if (j * pageSize < events.size() || rows.hasMore()) {
                updated = updated.withStart(from + j, events.get(j * pageSize - 1).id());
            }
        }
        if (!updated.equals(session) && !sessions.replace(sender, session, updated)) {
            log.debug("A newer lookup replaced the session of {} during a page walk", sender.getName());
        }
        int offset = (target - from) * pageSize;
        if (offset < events.size() || (offset == 0 && target == 1)) {
            int end = Math.min(events.size(), offset + pageSize);
            boolean hasMore = end < events.size() || rows.hasMore();
            return CompletableFuture.completedFuture(new PageResult.Shown(new PageView(session.query(), target,
                    pageSize, updated.total(), events.subList(offset, end), hasMore, rows.warnings())));
        }
        if (!rows.hasMore() || events.isEmpty()) {
            int last = from + Math.max(0, (events.size() + pageSize - 1) / pageSize - 1);
            return CompletableFuture.completedFuture(new PageResult.OutOfRange(events.isEmpty()
                    ? Math.max(1, from - 1) : last));
        }
        int next = from + events.size() / pageSize;
        return walk(sender, updated, next, target);
    }

    /**
     * The total shown with page 1: a lower bound is never below the rows page 1 found (the page and the
     * count may have read different segments), and it is exact when page 1 is the last.
     *
     * @param counted what the bounded count gave
     * @param first   page 1
     * @return the total
     */
    static @NotNull LookupTotal firstTotal(@NotNull LookupTotal counted, @NotNull LookupOutcome.Page first) {
        long seen = first.events().size() + (first.hasMore() ? 1 : 0);
        LookupTotal total = counted instanceof LookupTotal.AtLeast atLeast && atLeast.events() < seen
                ? new LookupTotal.AtLeast(seen) : counted;
        return ended(total, first.hasMore(), first.events().size());
    }

    /**
     * A lower bound becomes exact once the rows have ended: every event before the last read was on a
     * full page.
     *
     * @param total   the total so far
     * @param hasMore whether rows follow the last one read
     * @param read    the events from the newest to the last one read
     */
    private static @NotNull LookupTotal ended(@NotNull LookupTotal total, boolean hasMore, long read) {
        return total instanceof LookupTotal.AtLeast && !hasMore ? new LookupTotal.Exact(read) : total;
    }

    private static @NotNull List<LookupWarning> distinctShards(@NotNull List<LookupWarning> warnings) {
        Set<String> seen = new HashSet<>();
        return warnings.stream().filter(warning -> seen.add(warning.shard())).toList();
    }

    private static @NotNull PageResult failed(@NotNull Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        log.error("A lookup failed", cause);
        return new PageResult.Unavailable(new LookupOutcome.Failed(Objects.requireNonNullElse(cause.getMessage(),
                cause.getClass().getSimpleName())));
    }
}
