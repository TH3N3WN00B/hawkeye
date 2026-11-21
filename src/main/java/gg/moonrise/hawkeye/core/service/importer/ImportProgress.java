package gg.moonrise.hawkeye.core.service.importer;

import gg.moonrise.hawkeye.entity.importer.SkipReason;
import java.util.Map;
import org.jetbrains.annotations.NotNull;

/**
 * A snapshot of a running (or just finished) import. Counts are of this run unless they say otherwise.
 *
 * @param source        the source, as {@code SourceSettings#describe()} gives it (never the password)
 * @param resumed       whether this run continues an earlier import of the same source
 * @param importedTotal events imported from this source, earlier runs included
 * @param imported      events this run imported (committed)
 * @param rowsRead      source rows this run consumed
 * @param rowsRemaining an estimate of the source rows still to read (refreshed now and then)
 * @param skipped       rows this run did not import, by reason
 * @param displaced     events this run stored in a later second than their own (older than the id floor)
 * @param elapsedMillis how long this run has been going
 */
public record ImportProgress(
        @NotNull String source,
        boolean resumed,
        long importedTotal,
        long imported,
        long rowsRead,
        long rowsRemaining,
        @NotNull Map<SkipReason, Long> skipped,
        long displaced,
        long elapsedMillis) {

    /** Copies the skip counts. */
    public ImportProgress {
        skipped = Map.copyOf(skipped);
    }

    /**
     * How many rows were skipped for a reason.
     *
     * @param reason the reason
     * @return the count
     */
    public long skipped(@NotNull SkipReason reason) {
        return skipped.getOrDefault(reason, 0L);
    }

    /**
     * The rows skipped for every reason together.
     *
     * @return the sum
     */
    public long skippedTotal() {
        return skipped.values().stream().mapToLong(Long::longValue).sum();
    }

    /**
     * How much of the source this run has read.
     *
     * @return 0 to 100; 100 when nothing was left to read
     */
    public int percent() {
        long all = rowsRead + rowsRemaining;
        return all <= 0 ? 100 : (int) Math.min(100, rowsRead * 100 / all);
    }

    /**
     * How fast this run imports.
     *
     * @return events per second, 0 before the first second
     */
    public double eventsPerSecond() {
        return elapsedMillis <= 0 ? 0 : imported * 1000.0 / elapsedMillis;
    }

    /**
     * How long this run has been going.
     *
     * @return seconds
     */
    public double seconds() {
        return elapsedMillis / 1000.0;
    }
}
