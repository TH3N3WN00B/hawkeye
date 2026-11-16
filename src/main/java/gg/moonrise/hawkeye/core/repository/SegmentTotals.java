package gg.moonrise.hawkeye.core.repository;

/**
 * How much the archive of a shard holds.
 *
 * @param events   archived events
 * @param segments segments
 * @param bytes    stored header and body bytes (without the database's own overhead)
 */
public record SegmentTotals(long events, long segments, long bytes) {
}
