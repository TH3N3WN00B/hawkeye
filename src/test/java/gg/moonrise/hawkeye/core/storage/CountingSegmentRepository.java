package gg.moonrise.hawkeye.core.storage;

import gg.moonrise.hawkeye.core.repository.EventFilter;
import gg.moonrise.hawkeye.core.repository.SegmentRange;
import gg.moonrise.hawkeye.core.repository.SegmentRepository;
import gg.moonrise.hawkeye.core.repository.segment.ByteRange;
import gg.moonrise.hawkeye.core.sql.Sql;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** A segment repository that records which segment bodies were read and how the covering index was walked. */
public final class CountingSegmentRepository extends SegmentRepository {

    private final List<Long> bodyReads = new ArrayList<>();
    private final List<Integer> walks = new ArrayList<>();

    @Override
    public @NotNull List<byte[]> readRanges(@NotNull Connection connection, @NotNull Sql sql, long segment,
            @NotNull List<ByteRange> ranges) throws SQLException {
        bodyReads.add(segment);
        return super.readRanges(connection, sql, segment, ranges);
    }

    @Override
    public @NotNull List<SegmentRange> newestRanges(@NotNull Connection connection, @NotNull Sql sql,
            @NotNull EventFilter filter, long eventCursor, @Nullable SegmentRange after, int limit) throws SQLException {
        walks.add(limit);
        return super.newestRanges(connection, sql, filter, eventCursor, after, limit);
    }

    /**
     * The limits of the statements that walked the covering index since the last call, in order, and
     * forgets them.
     *
     * @return one limit per statement
     */
    public @NotNull List<Integer> takeWalks() {
        List<Integer> out = List.copyOf(walks);
        walks.clear();
        return out;
    }

    /**
     * The segments whose bodies were read since the last call, in order, and forgets them.
     *
     * @return the segment ids
     */
    public @NotNull List<Long> takeBodyReads() {
        List<Long> out = List.copyOf(bodyReads);
        bodyReads.clear();
        return out;
    }
}
