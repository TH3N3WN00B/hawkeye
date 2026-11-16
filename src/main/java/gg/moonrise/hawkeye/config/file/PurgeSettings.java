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
 * The {@code purge} section.
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
@ToString
@Configuration
public final class PurgeSettings {

    @Comment({"Delete events older than this many days automatically (one server per database does it).",
            "0 keeps everything."})
    private int retentionDays = 0;

    @Comment("In game, /hawkeye purge may only delete data older than this; the console is not limited.")
    private int playerMinimumDays = 30;

    @Comment("Rows deleted per statement; smaller batches hold locks for less time.")
    private int batchSize = 10_000;

    @Comment("Hours between automatic purges.")
    private int intervalHours = 6;

    /**
     * Validation problems.
     *
     * @return one message per problem
     */
    public @NotNull List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (retentionDays < 0) {
            problems.add("purge.retention-days must not be negative");
        }
        if (playerMinimumDays < 0) {
            problems.add("purge.player-minimum-days must not be negative");
        }
        if (batchSize < 100 || batchSize > 1_000_000) {
            problems.add("purge.batch-size must be between 100 and 1000000");
        }
        if (intervalHours < 1) {
            problems.add("purge.interval-hours must be at least 1");
        }
        return problems;
    }
}
