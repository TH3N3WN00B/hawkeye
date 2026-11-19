package gg.moonrise.hawkeye.core.service.lookup;

import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.entity.query.EventCount;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;

/**
 * Combines what every shard of a lookup returned. Event ids carry their time and interleave across
 * servers, so the newest-first pages of several shards merge into one by id. A shard that failed
 * becomes a warning next to the others' results; only when every shard failed is the lookup a failure.
 */
public final class LookupMerge {

    private LookupMerge() {
    }

    /**
     * Merges per-shard pages. Each shard was asked for {@code pageSize + 1} rows, so the newest
     * {@code pageSize + 1} of the union are exactly the global ones and the extra row tells whether
     * another page follows.
     *
     * @param results  each shard's newest-first rows, or why it could not be read
     * @param pageSize the page size
     * @return the page, or {@link LookupOutcome.Failed} when every shard failed
     */
    public static @NotNull LookupOutcome page(@NotNull List<ShardResult<List<StoredEvent>>> results, int pageSize) {
        List<LookupWarning> warnings = warnings(results);
        if (!results.isEmpty() && warnings.size() == results.size()) {
            return failed(warnings);
        }
        List<StoredEvent> merged = new ArrayList<>();
        for (ShardResult<List<StoredEvent>> result : results) {
            if (result instanceof ShardResult.Read<List<StoredEvent>> read) {
                merged.addAll(read.value());
            }
        }
        merged.sort(Comparator.comparingLong(StoredEvent::id).reversed());
        boolean hasMore = merged.size() > pageSize;
        return new LookupOutcome.Page(merged.subList(0, Math.min(merged.size(), pageSize)), hasMore, warnings);
    }

    /**
     * Adds per-shard counts; the total is exact only when every shard's count is.
     *
     * @param results each shard's count, or why it could not be read
     * @return the total, or {@link LookupOutcome.Failed} when every shard failed
     */
    public static @NotNull LookupOutcome count(@NotNull List<ShardResult<EventCount>> results) {
        List<LookupWarning> warnings = warnings(results);
        if (!results.isEmpty() && warnings.size() == results.size()) {
            return failed(warnings);
        }
        EventCount total = EventCount.of(0);
        for (ShardResult<EventCount> result : results) {
            if (result instanceof ShardResult.Read<EventCount> read) {
                total = total.plus(read.value());
            }
        }
        return new LookupOutcome.Count(total.total(), total.exact(), warnings);
    }

    private static <T> @NotNull List<LookupWarning> warnings(@NotNull List<ShardResult<T>> results) {
        List<LookupWarning> warnings = new ArrayList<>();
        for (ShardResult<T> result : results) {
            if (result instanceof ShardResult.Unreadable<T> unreadable) {
                warnings.add(new LookupWarning(unreadable.shard(), unreadable.reason()));
            }
        }
        return warnings;
    }

    private static @NotNull LookupOutcome failed(@NotNull List<LookupWarning> warnings) {
        if (warnings.size() == 1) {
            return new LookupOutcome.Failed(warnings.getFirst().reason());
        }
        return new LookupOutcome.Failed(warnings.stream()
                .map(warning -> warning.shard() + ": " + warning.reason())
                .collect(Collectors.joining("; ")));
    }
}
