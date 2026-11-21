package gg.moonrise.hawkeye.entity.importer;

/**
 * The last row consumed from a source table: the reader continues after it, by {@code rowId} in rowid
 * order and by {@code (time, rowId)} in time order.
 *
 * @param time  the row's time, Unix seconds
 * @param rowId the row's rowid
 */
public record SourceCursor(long time, long rowId) {

    /** Before the first row. */
    public static final SourceCursor START = new SourceCursor(0, 0);
}
