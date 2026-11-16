package gg.moonrise.hawkeye.config.file;

import de.exlll.configlib.Comment;
import de.exlll.configlib.Configuration;
import java.util.ArrayList;
import java.util.List;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;
import org.jetbrains.annotations.NotNull;

/**
 * The {@code archive} section: moving old events from the indexed hot table into packed segments.
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
@ToString
@Configuration
public final class ArchiveSettings {

    /** The fewest events per segment an operator may configure. */
    public static final int MIN_SEGMENT_EVENTS = 16_384;

    /** The most events per segment an operator may configure. */
    public static final int MAX_SEGMENT_EVENTS = 1_048_576;

    @Comment({"Pack old events into compressed archive segments (about 1-2 bytes per event on real servers,",
            "against 70 or more in the indexed table). Lookups, rollbacks and purges read both tiers the same way."})
    private boolean enabled = true;

    @Comment({"A segment is packed as soon as segment-events settled events are waiting. Events older than this many",
            "hours are packed even when fewer are waiting, so a quiet server is archived too (in smaller segments)."})
    private int afterHours = 24;

    @Comment("Minutes between archiving runs (one server per database does it).")
    private int intervalMinutes = 1;

    @Comment({"Events per segment, 16384 to 1048576. Larger segments compress better; a lookup about one place",
            "still reads only a small part of one."})
    private int segmentEvents = 262_144;

    @Comment({"Events newer than this many seconds are never packed, so a retried or late write lands before its",
            "neighbours are archived (5 to 3600)."})
    private int settleSeconds = 60;

    /**
     * A copy with other packing values, for tests and benchmarks.
     *
     * @param segmentEvents events per segment
     * @param settleSeconds the settle time in seconds
     * @param afterHours    the age in hours after which fewer events are packed
     * @return the copy
     */
    public @NotNull ArchiveSettings withPacking(int segmentEvents, int settleSeconds, int afterHours) {
        ArchiveSettings copy = new ArchiveSettings();
        copy.enabled = enabled;
        copy.intervalMinutes = intervalMinutes;
        copy.segmentEvents = segmentEvents;
        copy.settleSeconds = settleSeconds;
        copy.afterHours = afterHours;
        return copy;
    }

    /**
     * Validation problems.
     *
     * @return one message per problem
     */
    public @NotNull List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (afterHours < 1) {
            problems.add("archive.after-hours must be at least 1");
        }
        if (intervalMinutes < 1) {
            problems.add("archive.interval-minutes must be at least 1");
        }
        if (segmentEvents < MIN_SEGMENT_EVENTS || segmentEvents > MAX_SEGMENT_EVENTS) {
            problems.add("archive.segment-events must be between " + MIN_SEGMENT_EVENTS + " and " + MAX_SEGMENT_EVENTS);
        }
        if (settleSeconds < 5 || settleSeconds > 3_600) {
            problems.add("archive.settle-seconds must be between 5 and 3600");
        }
        return problems;
    }
}
