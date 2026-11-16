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

    @Comment({"Pack events older than after-hours into compressed segments (about a sixth of the space).",
            "Lookups, rollbacks and purges read both tiers the same way."})
    private boolean enabled = true;

    @Comment("Events older than this many hours are archived.")
    private int afterHours = 24;

    @Comment("Minutes between archiving runs (one server per database does it).")
    private int intervalMinutes = 30;

    @Comment("Events per archiving transaction; larger batches make larger, better-compressed segments.")
    private int batchEvents = 50_000;

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
        if (batchEvents < 1_000 || batchEvents > 1_000_000) {
            problems.add("archive.batch-events must be between 1000 and 1000000");
        }
        return problems;
    }
}
