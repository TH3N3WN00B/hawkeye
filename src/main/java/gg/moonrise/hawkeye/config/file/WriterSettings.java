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
 * The {@code writer} section.
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
@ToString
@Configuration
public final class WriterSettings {

    @Comment("Longest an event waits in memory before it is written (also how quickly other servers see it).")
    private long flushIntervalMillis = 500;

    @Comment("Events per transaction; a full batch is written at once without waiting for the interval.")
    private int batchSize = 2_000;

    @Comment({"Events held in memory while the database is slow or down. Beyond this, new events are dropped",
            "and counted in /hawkeye status; nothing ever blocks the game."})
    private int queueCapacity = 1_000_000;

    @Comment({"On shutdown the queue is written for up to this long; whatever is left is spooled to disk",
            "and written on the next start."})
    private int shutdownTimeoutSeconds = 15;

    @Comment("While the database is down, retries back off up to this long.")
    private int maxRetryDelaySeconds = 30;

    /**
     * Validation problems.
     *
     * @return one message per problem
     */
    public @NotNull List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (flushIntervalMillis < 50 || flushIntervalMillis > 60_000) {
            problems.add("writer.flush-interval-millis must be between 50 and 60000");
        }
        if (batchSize < 1 || batchSize > 50_000) {
            problems.add("writer.batch-size must be between 1 and 50000");
        }
        if (queueCapacity < batchSize) {
            problems.add("writer.queue-capacity must be at least writer.batch-size");
        }
        if (shutdownTimeoutSeconds < 1) {
            problems.add("writer.shutdown-timeout-seconds must be at least 1");
        }
        if (maxRetryDelaySeconds < 1) {
            problems.add("writer.max-retry-delay-seconds must be at least 1");
        }
        return problems;
    }
}
