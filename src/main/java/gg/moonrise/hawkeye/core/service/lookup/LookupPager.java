package gg.moonrise.hawkeye.core.service.lookup;

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
 * Opens lookups and turns their pages. Opening runs the first page and the count in parallel and
 * starts the sender's session ({@link LookupSessions}); turning to page {@code n} reads it from its
 * known start, or walks forward from the last known page when {@code n} is further, recording every
 * page start it passes. A walk reads up to {@link #WALK_ROWS} rows per query, so a long jump costs a
 * few queries rather than one per page.
 */
@Slf4j
@SpringComponent
@RequiredArgsConstructor
public final class LookupPager {

    /** The most rows one walking query reads. */
    public static final int WALK_ROWS = 1_000;

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
        CompletableFuture<LookupOutcome> count = lookupService.count(query);
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
                long total = count instanceof LookupOutcome.Count counted ? counted.total()
                        : LookupSession.UNKNOWN_TOTAL;
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
        LookupSession updated = session;
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
                    pageSize, session.total(), events.subList(offset, end), hasMore, rows.warnings())));
        }
        if (!rows.hasMore() || events.isEmpty()) {
            int last = from + Math.max(0, (events.size() + pageSize - 1) / pageSize - 1);
            return CompletableFuture.completedFuture(new PageResult.OutOfRange(events.isEmpty()
                    ? Math.max(1, from - 1) : last));
        }
        int next = from + events.size() / pageSize;
        return walk(sender, updated, next, target);
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
