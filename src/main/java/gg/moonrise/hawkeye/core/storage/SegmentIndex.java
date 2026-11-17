package gg.moonrise.hawkeye.core.storage;

import gg.moonrise.hawkeye.core.repository.EventFilter;
import gg.moonrise.hawkeye.core.repository.SegmentIndexRepository;
import gg.moonrise.hawkeye.core.repository.SegmentRepository;
import gg.moonrise.hawkeye.core.repository.SegmentRow;
import gg.moonrise.hawkeye.core.repository.place.ChunkRef;
import gg.moonrise.hawkeye.core.repository.place.IdRuns;
import gg.moonrise.hawkeye.core.repository.place.SegmentPlaces;
import gg.moonrise.hawkeye.core.repository.segment.ByteRange;
import gg.moonrise.hawkeye.core.repository.segment.SegmentHeader;
import gg.moonrise.hawkeye.core.repository.segment.UnitScan;
import gg.moonrise.hawkeye.core.sql.Database;
import gg.moonrise.hawkeye.core.sql.Sql;
import gg.moonrise.moss.spring.SpringComponent;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

/**
 * The segment index (docs/architecture.md, "Segment index"): which segments a lookup has to read. Its
 * place part is the {@link PlaceIndex}. It is changed in the transaction that writes or rewrites a
 * segment, trimmed after a purge, and caught up for segments written before it existed or after it was
 * emptied ({@code indexed_from}); every change takes the state lock first. A lookup reads only the
 * segments the index names, plus every segment below {@code indexed_from}.
 */
@SpringComponent
@RequiredArgsConstructor
public final class SegmentIndex {

    /** Segments indexed per catch-up transaction. */
    public static final int CATCH_UP_BATCH = 64;

    private final @NotNull SegmentIndexRepository state;
    private final @NotNull PlaceIndex places;
    private final @NotNull SegmentRepository segments;
    private final @NotNull SegmentCache cache;

    /**
     * What one catch-up call did.
     *
     * @param indexed how many segments it indexed
     * @param more    whether segments are still missing from the index
     */
    public record CatchUp(int indexed, boolean more) {
    }

    /**
     * Records a segment just written, in the writing transaction.
     *
     * @param connection a connection inside the transaction that inserted the segment
     * @param shard      the shard
     * @param placed     the places of the segment's rows
     * @throws SQLException when reading or writing fails
     */
    public void added(@NotNull Connection connection, @NotNull Shard shard, @NotNull SegmentPlaces placed)
            throws SQLException {
        Sql sql = shard.database().sql();
        state.lockIndexedFrom(connection, sql);
        places.apply(connection, shard, List.of(placed), Optional.empty(), segments.oldestSegmentId(connection, sql));
    }

    /**
     * Records that a segment was rewritten: its id leaves the rows its header names, and its replacement
     * (when anything was kept) is added.
     *
     * @param connection  a connection inside the rewriting transaction, after the old segment is deleted
     * @param shard       the shard
     * @param removed     the old segment
     * @param replacement the places of the new segment, when one was written
     * @throws SQLException when reading or writing fails
     */
    public void replaced(@NotNull Connection connection, @NotNull Shard shard, @NotNull RemovedSegment removed,
            @NotNull Optional<SegmentPlaces> replacement) throws SQLException {
        Sql sql = shard.database().sql();
        state.lockIndexedFrom(connection, sql);
        places.apply(connection, shard, replacement.stream().toList(), Optional.of(removed),
                segments.oldestSegmentId(connection, sql));
    }

    /**
     * Deletes the rows that name no stored segment any more (after a purge).
     *
     * @param connection a connection inside the purging transaction
     * @param shard      the shard
     * @return the number of rows deleted
     * @throws SQLException when reading or writing fails
     */
    public int trim(@NotNull Connection connection, @NotNull Shard shard) throws SQLException {
        Sql sql = shard.database().sql();
        state.lockIndexedFrom(connection, sql);
        long oldest = segments.oldestSegmentId(connection, sql);
        return places.trim(connection, sql, oldest == 0 ? Long.MAX_VALUE : oldest);
    }

    /**
     * Indexes up to some of the segments below {@code indexed_from}, newest first, a batch per
     * transaction, lowering {@code indexed_from} with each batch.
     *
     * @param shard       the shard
     * @param maxSegments the most segments to index
     * @return how many were indexed and whether more are missing
     * @throws SQLException when reading or writing fails
     */
    public @NotNull CatchUp catchUp(@NotNull Shard shard, int maxSegments) throws SQLException {
        Database database = shard.database();
        int done = 0;
        while (done < maxSegments) {
            int batch = Math.min(CATCH_UP_BATCH, maxSegments - done);
            int indexed = database.transaction(connection -> catchUpBatch(connection, shard, batch));
            if (indexed == 0) {
                break;
            }
            done += indexed;
        }
        boolean more = database.read(connection -> missing(connection, database.sql()));
        return new CatchUp(done, more);
    }

    /**
     * The segments a lookup has to read: those the index names for the filter, plus every segment below
     * {@code indexed_from}. Empty when the index does not narrow the filter (no area, or one larger than
     * {@link PlaceIndex#MAX_AREA_CHUNKS}): then every candidate's header decides.
     *
     * @param connection a connection
     * @param shard      the shard
     * @param filter     the filter
     * @return the segment ids (some may no longer exist)
     * @throws SQLException when reading fails
     */
    public @NotNull Optional<IdRuns> plan(@NotNull Connection connection, @NotNull Shard shard,
            @NotNull EventFilter filter) throws SQLException {
        Optional<NavigableMap<ChunkRef, Boolean>> chunks = places.chunks(filter);
        if (chunks.isEmpty()) {
            return Optional.empty();
        }
        // Read before the rows: catching up only adds rows for segments below this value.
        Optional<Long> indexedFrom = state.indexedFrom(connection, shard.database().sql());
        if (indexedFrom.isEmpty()) {
            return Optional.empty();
        }
        IdRuns out = places.plan(connection, shard, filter, chunks.get());
        if (indexedFrom.get() > 1) {
            out.addAll(IdRuns.range(0, indexedFrom.get() - 1));
        }
        return Optional.of(out);
    }

    private int catchUpBatch(@NotNull Connection connection, @NotNull Shard shard, int batch) throws SQLException {
        Sql sql = shard.database().sql();
        long indexedFrom = state.lockIndexedFrom(connection, sql);
        List<Long> ids = segments.idsBelow(connection, sql, indexedFrom, batch);
        if (ids.isEmpty()) {
            return 0;
        }
        List<SegmentPlaces> added = new ArrayList<>(ids.size());
        for (SegmentRow segment : segments.rows(connection, sql, ids)) {
            added.add(placesOf(connection, shard, segment));
        }
        places.apply(connection, shard, added, Optional.empty(), segments.oldestSegmentId(connection, sql));
        state.setIndexedFrom(connection, sql, ids.getLast());
        return ids.size();
    }

    private boolean missing(@NotNull Connection connection, @NotNull Sql sql) throws SQLException {
        Optional<Long> indexedFrom = state.indexedFrom(connection, sql);
        return indexedFrom.isPresent() && !segments.idsBelow(connection, sql, indexedFrom.get(), 1).isEmpty();
    }

    /** The places of a stored segment's rows, from its units' positions (nothing else is decoded). */
    private @NotNull SegmentPlaces placesOf(@NotNull Connection connection, @NotNull Shard shard,
            @NotNull SegmentRow segment) throws SQLException {
        SegmentHeader header = cache.header(shard.database(), segment.id(), segment.header());
        List<ByteRange> ranges = new ArrayList<>(header.unitCount());
        for (int unit = 0; unit < header.unitCount(); unit++) {
            ranges.add(header.unit(unit));
        }
        List<byte[]> frames = segments.readRanges(connection, shard.database().sql(), segment.id(), ranges);
        int[] worldIds = header.worldIds();
        SegmentPlaces.Builder builder = SegmentPlaces.builder(segment.id());
        for (int unit = 0; unit < header.unitCount(); unit++) {
            byte[] bytes = frames.get(unit);
            UnitScan scan = new UnitScan(header, unit, bytes, 0, bytes.length);
            int[] worlds = scan.worldIndexes();
            long[] positions = scan.positions();
            for (int row = 0; row < positions.length; row++) {
                builder.add(worldIds[worlds[row]], positions[row]);
            }
        }
        return builder.build();
    }
}
