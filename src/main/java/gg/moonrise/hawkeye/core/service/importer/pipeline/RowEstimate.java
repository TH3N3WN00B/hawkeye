package gg.moonrise.hawkeye.core.service.importer.pipeline;

import gg.moonrise.hawkeye.entity.importer.source.SourceCursor;
import gg.moonrise.hawkeye.entity.importer.source.SourceTable;
import java.sql.SQLException;
import java.util.Collection;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

/**
 * The run's estimate of the source rows still to read, for progress only. Refreshing it asks the source
 * per table ({@code CoreProtectSource#remaining}; on ClickHouse a count that can time out): a failure keeps
 * the previous estimate (0 before the first) and never fails the import. The first failure is logged at
 * DEBUG, later ones are not. Used by the import thread only.
 */
@Slf4j
public final class RowEstimate {

    private final @NotNull Counter counter;
    private final @NotNull String description;
    private long last;
    private boolean failed;

    /**
     * An estimate with nothing counted yet.
     *
     * @param counter     counts the rows after a cursor
     * @param description the source, for the log
     */
    public RowEstimate(@NotNull Counter counter, @NotNull String description) {
        this.counter = counter;
        this.description = description;
    }

    /**
     * Counts again.
     *
     * @param tables  the tables
     * @param cursors where each table continues
     * @return the new estimate, or the previous one when counting failed
     */
    public long refresh(@NotNull Collection<SourceTable> tables, @NotNull Function<SourceTable, SourceCursor> cursors) {
        long sum = 0;
        try {
            for (SourceTable table : tables) {
                sum += counter.remaining(table, cursors.apply(table));
            }
        } catch (SQLException e) {
            if (!failed) {
                failed = true;
                log.debug("Could not estimate the rows left in {}; progress keeps the previous estimate",
                        description, e);
            }
            return last;
        }
        last = sum;
        return sum;
    }

    /**
     * The latest estimate.
     *
     * @return rows, 0 before the first count
     */
    public long last() {
        return last;
    }

    /** Counts the rows of one table after a cursor. */
    @FunctionalInterface
    public interface Counter {

        /**
         * About how many rows follow a cursor.
         *
         * @param table the table
         * @param after the last row consumed
         * @return the estimate
         * @throws SQLException when counting fails
         */
        long remaining(@NotNull SourceTable table, @NotNull SourceCursor after) throws SQLException;
    }
}
