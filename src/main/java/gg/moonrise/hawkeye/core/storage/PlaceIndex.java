package gg.moonrise.hawkeye.core.storage;

import gg.moonrise.hawkeye.core.repository.EventFilter;
import gg.moonrise.hawkeye.core.repository.EventFilter.WorldRange;
import gg.moonrise.hawkeye.core.repository.PlaceIndexRepository;
import gg.moonrise.hawkeye.core.repository.PlaceIndexRepository.Kind;
import gg.moonrise.hawkeye.core.repository.SegmentRepository;
import gg.moonrise.hawkeye.core.repository.SegmentRow;
import gg.moonrise.hawkeye.core.repository.place.ChunkRef;
import gg.moonrise.hawkeye.core.repository.place.IdRuns;
import gg.moonrise.hawkeye.core.repository.place.PlaceChunk;
import gg.moonrise.hawkeye.core.repository.place.PlaceKeys;
import gg.moonrise.hawkeye.core.repository.place.PlaceRegion;
import gg.moonrise.hawkeye.core.repository.place.RegionRef;
import gg.moonrise.hawkeye.core.repository.place.SegmentPlaces;
import gg.moonrise.hawkeye.core.repository.segment.ByteRange;
import gg.moonrise.hawkeye.core.repository.segment.Regions;
import gg.moonrise.hawkeye.core.repository.segment.SegmentHeader;
import gg.moonrise.hawkeye.core.repository.segment.UnitScan;
import gg.moonrise.hawkeye.core.sql.Database;
import gg.moonrise.hawkeye.core.sql.Sql;
import gg.moonrise.hawkeye.entity.query.RollbackState;
import gg.moonrise.moss.spring.SpringComponent;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The place index (docs/architecture.md, "Place index"): which segments hold rows in which chunk
 * ({@code place_region}), and for a chunk in more than {@link #BUSY_SEGMENTS} segments which buckets of
 * 16 segments touched which position ({@code place_chunk}). It is changed in the transaction that
 * writes or rewrites a segment, trimmed after a purge, and caught up for segments written before it
 * existed ({@code indexed_from}). A lookup about a place reads only the segments it names, plus every
 * segment below {@code indexed_from}.
 */
@SpringComponent
public final class PlaceIndex {

    /** A chunk in more segments than this gets a {@code place_chunk} row. */
    public static final int BUSY_SEGMENTS = 16;

    /**
     * An area covering more chunks than this is read the old way (every header): 1,024 chunks is one
     * region of 32 x 32, at most a few region rows and up to 1,024 busy rows (about 1.2 KB compressed
     * each), against a header test per segment that is cached and costs microseconds.
     */
    public static final int MAX_AREA_CHUNKS = 1_024;

    /** Segments indexed per catch-up transaction. */
    public static final int CATCH_UP_BATCH = 64;

    /** The zstd level of index rows: rewritten on every pack, so cheaper than the segments' 19. */
    public static final int LEVEL = 9;

    private final @NotNull PlaceIndexRepository places;
    private final @NotNull SegmentRepository segments;
    private final @NotNull SegmentCache cache;
    private final int busySegments;

    /**
     * The index with the production threshold.
     *
     * @param places   the index tables
     * @param segments the segment table
     * @param cache    the parsed-header cache shared with {@link Archive}
     */
    @Autowired
    public PlaceIndex(@NotNull PlaceIndexRepository places, @NotNull SegmentRepository segments,
            @NotNull SegmentCache cache) {
        this(places, segments, cache, BUSY_SEGMENTS);
    }

    /**
     * The index with another busy threshold, so tests make chunks cross it with a few segments.
     *
     * @param places       the index tables
     * @param segments     the segment table
     * @param cache        the parsed-header cache
     * @param busySegments a chunk in more segments than this gets a {@code place_chunk} row
     */
    PlaceIndex(@NotNull PlaceIndexRepository places, @NotNull SegmentRepository segments, @NotNull SegmentCache cache,
            int busySegments) {
        this.places = places;
        this.segments = segments;
        this.cache = cache;
        this.busySegments = busySegments;
    }

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
        places.lockIndexedFrom(connection, shard.database().sql());
        apply(connection, shard, List.of(placed), Optional.empty());
    }

    /**
     * Records that a segment was rewritten: its id leaves the rows of the regions its header lists, and
     * its replacement (when anything was kept) is added.
     *
     * @param connection  a connection inside the rewriting transaction, after the old segment is deleted
     * @param shard       the shard
     * @param old         the old segment id
     * @param oldHeader   its header
     * @param replacement the places of the new segment, when one was written
     * @throws SQLException when reading or writing fails
     */
    public void replaced(@NotNull Connection connection, @NotNull Shard shard, long old,
            @NotNull SegmentHeader oldHeader, @NotNull Optional<SegmentPlaces> replacement) throws SQLException {
        places.lockIndexedFrom(connection, shard.database().sql());
        apply(connection, shard, replacement.stream().toList(), Optional.of(new Removal(old, regionsOf(oldHeader))));
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
        places.lockIndexedFrom(connection, sql);
        long oldest = segments.oldestSegmentId(connection, sql);
        return places.deleteOlderThan(connection, sql, oldest == 0 ? Long.MAX_VALUE : oldest);
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
     * The segments a lookup has to read: those the index names for the filter's area, plus every
     * segment below {@code indexed_from}. Empty when the filter has no area, or one larger than
     * {@link #MAX_AREA_CHUNKS}: then every candidate's header decides, as before the index.
     *
     * @param connection a connection
     * @param shard      the shard
     * @param filter     the filter
     * @return the segment ids (some may no longer exist)
     * @throws SQLException when reading fails
     */
    public @NotNull Optional<IdRuns> plan(@NotNull Connection connection, @NotNull Shard shard,
            @NotNull EventFilter filter) throws SQLException {
        if (!filter.hasArea()) {
            return Optional.empty();
        }
        TreeMap<ChunkRef, Boolean> chunks = new TreeMap<>();
        long covered = 0;
        for (WorldRange range : filter.positions()) {
            if (range.from() > range.to()) {
                continue;
            }
            long first = PlaceKeys.chunk(range.from());
            long last = PlaceKeys.chunk(range.to());
            covered += last - first + 1;
            if (covered > MAX_AREA_CHUNKS) {
                return Optional.empty();
            }
            for (long chunk = first; chunk <= last; chunk++) {
                chunks.merge(new ChunkRef(range.world(), chunk), wholeChunk(filter, range, chunk), Boolean::logicalOr);
            }
        }
        Sql sql = shard.database().sql();
        Optional<Long> indexedFrom = places.indexedFrom(connection, sql);
        if (indexedFrom.isEmpty()) {
            return Optional.empty();
        }
        Set<RegionRef> regionRefs = new TreeSet<>();
        chunks.keySet().forEach(chunk -> regionRefs.add(chunk.region()));
        Map<RegionRef, PlaceRegion> regions = readRegions(connection, sql, regionRefs);
        IdRuns out = new IdRuns();
        Map<ChunkRef, IdRuns> partial = new TreeMap<>();
        chunks.forEach((chunk, whole) -> {
            PlaceRegion region = regions.get(chunk.region());
            Optional<IdRuns> ids = region == null ? Optional.empty() : region.find(chunk.indexInRegion());
            if (ids.isEmpty()) {
                return;
            }
            if (whole || !region.busy(chunk.indexInRegion())) {
                out.addAll(ids.get());
            } else {
                partial.put(chunk, ids.get());
            }
        });
        Map<ChunkRef, byte[]> bodies = readBodies(connection, sql, Kind.CHUNK, partial.keySet());
        for (Map.Entry<ChunkRef, IdRuns> entry : partial.entrySet()) {
            byte[] body = bodies.get(entry.getKey());
            if (body == null) {
                out.addAll(entry.getValue());
                continue;
            }
            int world = entry.getKey().world();
            long base = PlaceKeys.base(entry.getKey().chunk());
            IdRuns buckets = PlaceChunk.bucketsWhere(body, local -> filter.inPositions(world, base | local));
            out.addAll(entry.getValue().inBuckets(buckets, PlaceChunk.BUCKET_SHIFT));
        }
        if (indexedFrom.get() > 1) {
            out.addAll(IdRuns.range(0, indexedFrom.get() - 1));
        }
        return Optional.of(out);
    }

    /**
     * The places of a stored segment's rows, from its units' positions (nothing else is decoded).
     *
     * @param connection a connection
     * @param shard      the shard
     * @param segment    the segment
     * @return the places
     * @throws SQLException when reading fails
     */
    public @NotNull SegmentPlaces placesOf(@NotNull Connection connection, @NotNull Shard shard,
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

    private int catchUpBatch(@NotNull Connection connection, @NotNull Shard shard, int batch) throws SQLException {
        Sql sql = shard.database().sql();
        long indexedFrom = places.lockIndexedFrom(connection, sql);
        List<Long> ids = segments.idsBelow(connection, sql, indexedFrom, batch);
        if (ids.isEmpty()) {
            return 0;
        }
        List<SegmentPlaces> added = new ArrayList<>(ids.size());
        for (SegmentRow segment : segments.rows(connection, sql, ids)) {
            added.add(placesOf(connection, shard, segment));
        }
        apply(connection, shard, added, Optional.empty());
        places.setIndexedFrom(connection, sql, ids.getLast());
        return ids.size();
    }

    private boolean missing(@NotNull Connection connection, @NotNull Sql sql) throws SQLException {
        Optional<Long> indexedFrom = places.indexedFrom(connection, sql);
        return indexedFrom.isPresent() && !segments.idsBelow(connection, sql, indexedFrom.get(), 1).isEmpty();
    }

    /** A segment id leaving the rows of some regions. */
    private record Removal(long segment, @NotNull Set<RegionRef> regions) {
    }

    /**
     * The one write path: reads the touched rows, applies additions and a removal, trims what no longer
     * exists, builds the rows of chunks that became busy and writes everything back. The caller holds
     * the state lock.
     */
    private void apply(@NotNull Connection connection, @NotNull Shard shard, @NotNull List<SegmentPlaces> added,
            @NotNull Optional<Removal> removal) throws SQLException {
        Sql sql = shard.database().sql();
        long oldest = segments.oldestSegmentId(connection, sql);
        Set<RegionRef> touched = new TreeSet<>();
        Set<ChunkRef> addedChunks = new TreeSet<>();
        Set<Long> inMemory = new HashSet<>();
        for (SegmentPlaces placed : added) {
            inMemory.add(placed.segment());
            for (ChunkRef chunk : placed.chunks().keySet()) {
                touched.add(chunk.region());
                addedChunks.add(chunk);
            }
        }
        removal.ifPresent(gone -> touched.addAll(gone.regions()));
        Map<RegionRef, PlaceRegion> regions = readRegions(connection, sql, touched);
        for (RegionRef ref : touched) {
            regions.computeIfAbsent(ref, missing -> new PlaceRegion(missing.world(), missing.region()));
        }
        removal.ifPresent(gone -> gone.regions().forEach(ref -> regions.get(ref).removeEverywhere(gone.segment())));
        for (SegmentPlaces placed : added) {
            for (ChunkRef chunk : placed.chunks().keySet()) {
                regions.get(chunk.region()).segmentsOf(chunk.indexInRegion()).add(placed.segment());
            }
        }
        Set<ChunkRef> deadRows = new TreeSet<>();
        regions.forEach((ref, region) -> region.trim(oldest).forEach(index -> deadRows.add(ref.chunk(index))));

        // Busy rows a batch at a time: decoded, merged, encoded and written, so memory stays bounded.
        List<ChunkRef> chunkList = new ArrayList<>(addedChunks);
        for (int from = 0; from < chunkList.size(); from += PlaceIndexRepository.READ_BATCH) {
            List<ChunkRef> batch = chunkList.subList(from, Math.min(chunkList.size(),
                    from + PlaceIndexRepository.READ_BATCH));
            Map<ChunkRef, PlaceChunk> rows = busyRows(connection, shard, regions, batch, inMemory);
            for (Map.Entry<ChunkRef, PlaceChunk> entry : rows.entrySet()) {
                for (SegmentPlaces placed : added) {
                    int[] locals = placed.chunks().get(entry.getKey());
                    if (locals != null) {
                        for (int local : locals) {
                            entry.getValue().add(local, placed.segment());
                        }
                    }
                }
                entry.getValue().trim(oldest);
            }
            writeChunkRows(connection, sql, regions, rows);
        }
        writeRegions(connection, sql, regions, deadRows);
    }

    /**
     * The {@code place_chunk} rows of the added chunks that are busy: existing rows read, and rows built
     * for chunks that crossed {@link #busySegments} (or whose row is missing) from every segment the
     * region row lists for them; segments being added in this call are left to the caller.
     */
    private @NotNull Map<ChunkRef, PlaceChunk> busyRows(@NotNull Connection connection, @NotNull Shard shard,
            @NotNull Map<RegionRef, PlaceRegion> regions, @NotNull List<ChunkRef> addedChunks,
            @NotNull Set<Long> inMemory) throws SQLException {
        Sql sql = shard.database().sql();
        Set<ChunkRef> existing = new TreeSet<>();
        Set<ChunkRef> crossing = new TreeSet<>();
        for (ChunkRef chunk : addedChunks) {
            PlaceRegion region = regions.get(chunk.region());
            int index = chunk.indexInRegion();
            Optional<IdRuns> ids = region.find(index);
            if (ids.isEmpty()) {
                continue;
            }
            if (region.busy(index)) {
                existing.add(chunk);
            } else if (ids.get().count() > busySegments) {
                crossing.add(chunk);
            }
        }
        Map<ChunkRef, PlaceChunk> rows = new TreeMap<>();
        Map<ChunkRef, byte[]> bodies = readBodies(connection, sql, Kind.CHUNK, existing);
        for (ChunkRef chunk : existing) {
            byte[] body = bodies.get(chunk);
            if (body == null) {
                crossing.add(chunk);
            } else {
                rows.put(chunk, PlaceChunk.fromBody(chunk.world(), chunk.chunk(), body));
            }
        }
        Map<ChunkRef, PlaceChunk> built = new TreeMap<>();
        Map<Long, List<ChunkRef>> bySegment = new TreeMap<>();
        for (ChunkRef chunk : crossing) {
            built.put(chunk, new PlaceChunk(chunk.world(), chunk.chunk()));
            regions.get(chunk.region()).find(chunk.indexInRegion()).orElseThrow().forEach(segment -> {
                if (!inMemory.contains(segment)) {
                    bySegment.computeIfAbsent(segment, ignored -> new ArrayList<>()).add(chunk);
                }
            });
            regions.get(chunk.region()).setBusy(chunk.indexInRegion(), true);
        }
        fill(connection, shard, built, bySegment);
        rows.putAll(built);
        return rows;
    }

    /** Adds the positions stored segments hold in some chunks to those chunks' new rows. */
    private void fill(@NotNull Connection connection, @NotNull Shard shard, @NotNull Map<ChunkRef, PlaceChunk> built,
            @NotNull Map<Long, List<ChunkRef>> bySegment) throws SQLException {
        if (bySegment.isEmpty()) {
            return;
        }
        Sql sql = shard.database().sql();
        for (SegmentRow segment : segments.rows(connection, sql, new ArrayList<>(bySegment.keySet()))) {
            SegmentHeader header = cache.header(shard.database(), segment.id(), segment.header());
            List<WorldRange> ranges = new ArrayList<>();
            for (ChunkRef chunk : bySegment.get(segment.id())) {
                long base = PlaceKeys.base(chunk.chunk());
                ranges.add(WorldRange.of(chunk.world(), base, base | PlaceKeys.LOCAL_MASK));
            }
            EventFilter area = new EventFilter(0, Long.MAX_VALUE, Set.of(), ranges, EventFilter.Bounds.NONE, Set.of(),
                    Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), RollbackState.ANY);
            int[] units = header.units(area);
            if (units.length == 0) {
                continue;
            }
            List<ByteRange> unitRanges = new ArrayList<>(units.length);
            for (int unit : units) {
                unitRanges.add(header.unit(unit));
            }
            List<byte[]> frames = segments.readRanges(connection, sql, segment.id(), unitRanges);
            int[] worldIds = header.worldIds();
            for (int i = 0; i < units.length; i++) {
                byte[] bytes = frames.get(i);
                UnitScan scan = new UnitScan(header, units[i], bytes, 0, bytes.length);
                int[] worlds = scan.worldIndexes();
                long[] positions = scan.positions();
                for (int row = 0; row < positions.length; row++) {
                    PlaceChunk target = built.get(new ChunkRef(worldIds[worlds[row]], PlaceKeys.chunk(positions[row])));
                    if (target != null) {
                        target.add(PlaceKeys.local(positions[row]), segment.id());
                    }
                }
            }
        }
    }

    private void writeRegions(@NotNull Connection connection, @NotNull Sql sql,
            @NotNull Map<RegionRef, PlaceRegion> regions, @NotNull Set<ChunkRef> deadRows) throws SQLException {
        Map<Integer, List<PlaceIndexRepository.Row>> regionRows = new TreeMap<>();
        Map<Integer, List<Long>> emptyRegions = new TreeMap<>();
        for (PlaceRegion region : regions.values()) {
            if (region.isEmpty()) {
                emptyRegions.computeIfAbsent(region.world(), ignored -> new ArrayList<>()).add(region.region());
            } else {
                regionRows.computeIfAbsent(region.world(), ignored -> new ArrayList<>())
                        .add(new PlaceIndexRepository.Row(region.region(), region.newest(), region.toBody(LEVEL)));
            }
        }
        Map<Integer, List<Long>> deadChunks = new TreeMap<>();
        deadRows.forEach(chunk -> deadChunks.computeIfAbsent(chunk.world(), ignored -> new ArrayList<>())
                .add(chunk.chunk()));
        for (Map.Entry<Integer, List<Long>> entry : emptyRegions.entrySet()) {
            places.delete(connection, sql, Kind.REGION, entry.getKey(), entry.getValue());
        }
        for (Map.Entry<Integer, List<PlaceIndexRepository.Row>> entry : regionRows.entrySet()) {
            places.write(connection, sql, Kind.REGION, entry.getKey(), entry.getValue());
        }
        for (Map.Entry<Integer, List<Long>> entry : deadChunks.entrySet()) {
            places.delete(connection, sql, Kind.CHUNK, entry.getKey(), entry.getValue());
        }
    }

    private void writeChunkRows(@NotNull Connection connection, @NotNull Sql sql,
            @NotNull Map<RegionRef, PlaceRegion> regions, @NotNull Map<ChunkRef, PlaceChunk> rows) throws SQLException {
        Map<Integer, List<PlaceIndexRepository.Row>> chunkRows = new TreeMap<>();
        Map<Integer, List<Long>> emptyChunks = new TreeMap<>();
        for (Map.Entry<ChunkRef, PlaceChunk> entry : rows.entrySet()) {
            ChunkRef chunk = entry.getKey();
            Optional<IdRuns> ids = regions.get(chunk.region()).find(chunk.indexInRegion());
            if (ids.isEmpty() || entry.getValue().isEmpty()) {
                emptyChunks.computeIfAbsent(chunk.world(), ignored -> new ArrayList<>()).add(chunk.chunk());
                continue;
            }
            chunkRows.computeIfAbsent(chunk.world(), ignored -> new ArrayList<>())
                    .add(new PlaceIndexRepository.Row(chunk.chunk(), ids.get().max(), entry.getValue().toBody(LEVEL)));
        }
        for (Map.Entry<Integer, List<Long>> entry : emptyChunks.entrySet()) {
            places.delete(connection, sql, Kind.CHUNK, entry.getKey(), entry.getValue());
        }
        for (Map.Entry<Integer, List<PlaceIndexRepository.Row>> entry : chunkRows.entrySet()) {
            places.write(connection, sql, Kind.CHUNK, entry.getKey(), entry.getValue());
        }
    }

    private @NotNull Map<RegionRef, PlaceRegion> readRegions(@NotNull Connection connection, @NotNull Sql sql,
            @NotNull Set<RegionRef> refs) throws SQLException {
        Map<RegionRef, PlaceRegion> out = new HashMap<>();
        Map<Integer, List<Long>> byWorld = new TreeMap<>();
        refs.forEach(ref -> byWorld.computeIfAbsent(ref.world(), ignored -> new ArrayList<>()).add(ref.region()));
        for (Map.Entry<Integer, List<Long>> entry : byWorld.entrySet()) {
            int world = entry.getKey();
            places.read(connection, sql, Kind.REGION, world, entry.getValue()).forEach((key, body) ->
                    out.put(new RegionRef(world, key), PlaceRegion.fromBody(world, key, body)));
        }
        return out;
    }

    private @NotNull Map<ChunkRef, byte[]> readBodies(@NotNull Connection connection, @NotNull Sql sql,
            @NotNull Kind kind, @NotNull Collection<ChunkRef> refs) throws SQLException {
        Map<ChunkRef, byte[]> out = new HashMap<>();
        Map<Integer, List<Long>> byWorld = new TreeMap<>();
        refs.forEach(ref -> byWorld.computeIfAbsent(ref.world(), ignored -> new ArrayList<>()).add(ref.chunk()));
        for (Map.Entry<Integer, List<Long>> entry : byWorld.entrySet()) {
            int world = entry.getKey();
            places.read(connection, sql, kind, world, entry.getValue()).forEach((key, body) ->
                    out.put(new ChunkRef(world, key), body));
        }
        return out;
    }

    /** Whether a range accepts every position of a chunk (no partial test is needed there). */
    private static boolean wholeChunk(@NotNull EventFilter filter, @NotNull WorldRange range, long chunk) {
        long base = PlaceKeys.base(chunk);
        return !filter.bounds().active() && !range.trimsX() && range.from() <= base
                && range.to() >= (base | PlaceKeys.LOCAL_MASK);
    }

    /** Every region a segment's header says it holds rows in (placed chunks and unplaced regions). */
    private static @NotNull Set<RegionRef> regionsOf(@NotNull SegmentHeader header) {
        Set<RegionRef> out = new TreeSet<>();
        int[] worldIds = header.worldIds();
        int[] chunkWorlds = header.chunkWorlds();
        long[] chunkBases = header.chunkBases();
        for (int i = 0; i < chunkBases.length; i++) {
            out.add(new ChunkRef(worldIds[chunkWorlds[i]], PlaceKeys.chunk(chunkBases[i])).region());
        }
        Regions unplaced = header.unplacedRegions();
        for (int i = 0; i < unplaced.worlds().length; i++) {
            out.add(new RegionRef(worldIds[unplaced.worlds()[i]], PlaceKeys.regionOf(unplaced.xs()[i],
                    unplaced.zs()[i])));
        }
        return out;
    }

    @Override
    public @NotNull String toString() {
        return "PlaceIndex[busySegments=" + busySegments + "]";
    }
}
