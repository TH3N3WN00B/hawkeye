package gg.moonrise.hawkeye.core.storage;

import gg.moonrise.hawkeye.core.repository.segment.SegmentHeader;
import org.jetbrains.annotations.NotNull;

/**
 * A segment that a rewrite deleted, for the segment index: its id leaves every row its header names.
 *
 * @param segment the segment id
 * @param header  its header
 */
public record RemovedSegment(long segment, @NotNull SegmentHeader header) {
}
