package gg.moonrise.hawkeye.core.storage;

import gg.moonrise.hawkeye.core.repository.SegmentRepository;
import gg.moonrise.hawkeye.core.repository.segment.ByteRange;
import gg.moonrise.hawkeye.core.sql.Sql;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.NotNull;

/** A segment repository that records which segment bodies were read. */
public final class CountingSegmentRepository extends SegmentRepository {

    private final List<Long> bodyReads = new ArrayList<>();

    @Override
    public @NotNull List<byte[]> readRanges(@NotNull Connection connection, @NotNull Sql sql, long segment,
            @NotNull List<ByteRange> ranges) throws SQLException {
        bodyReads.add(segment);
        return super.readRanges(connection, sql, segment, ranges);
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
