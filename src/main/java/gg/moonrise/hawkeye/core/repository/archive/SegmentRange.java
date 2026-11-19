package gg.moonrise.hawkeye.core.repository.archive;

/**
 * A segment's id and event id range, without its header: what a planned read orders its candidates by
 * before it fetches any header.
 *
 * @param id      the segment id
 * @param firstId the smallest event id inside
 * @param lastId  the largest event id inside
 */
public record SegmentRange(long id, long firstId, long lastId) {
}
