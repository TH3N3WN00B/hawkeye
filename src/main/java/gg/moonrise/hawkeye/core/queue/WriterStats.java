package gg.moonrise.hawkeye.core.queue;

import org.jetbrains.annotations.NotNull;

/**
 * A snapshot of the writer's counters for {@code /hawkeye status}.
 *
 * @param queued          events waiting
 * @param written         events written since start
 * @param batches         batches written since start
 * @param dropped         events dropped because the queue was full
 * @param spooled         events spooled to disk since start
 * @param failures        failed batch attempts since start
 * @param lastFlushMillis how long the last batch took
 * @param lastError       the last write error, or empty
 */
public record WriterStats(
        int queued,
        long written,
        long batches,
        long dropped,
        long spooled,
        long failures,
        long lastFlushMillis,
        @NotNull String lastError) {
}
