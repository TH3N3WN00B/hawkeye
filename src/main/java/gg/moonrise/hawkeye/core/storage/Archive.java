package gg.moonrise.hawkeye.core.storage;

import gg.moonrise.hawkeye.config.file.ArchiveSettings;
import gg.moonrise.hawkeye.core.repository.EventFilter;
import gg.moonrise.hawkeye.core.repository.EventRepository;
import gg.moonrise.hawkeye.core.repository.EventRow;
import gg.moonrise.hawkeye.core.repository.SegmentRange;
import gg.moonrise.hawkeye.core.repository.SegmentRepository;
import gg.moonrise.hawkeye.core.repository.SegmentRow;
import gg.moonrise.hawkeye.core.repository.SegmentTotals;
import gg.moonrise.hawkeye.core.repository.place.IdRuns;
import gg.moonrise.hawkeye.core.repository.place.SegmentPlaces;
import gg.moonrise.hawkeye.core.repository.segment.ByteRange;
import gg.moonrise.hawkeye.core.repository.segment.ColumnRules;
import gg.moonrise.hawkeye.core.repository.segment.PackedSegment;
import gg.moonrise.hawkeye.core.repository.segment.Palettes;
import gg.moonrise.hawkeye.core.repository.segment.SegmentHeader;
import gg.moonrise.hawkeye.core.repository.segment.SegmentMatch;
import gg.moonrise.hawkeye.core.repository.segment.Segments;
import gg.moonrise.hawkeye.core.repository.segment.SharedColumns;
import gg.moonrise.hawkeye.core.sql.Database;
import gg.moonrise.hawkeye.core.sql.Sql;
import gg.moonrise.hawkeye.entity.query.RollbackState;
import gg.moonrise.hawkeye.entity.server.WorldKey;
import gg.moonrise.hawkeye.util.EventIds;
import gg.moonrise.moss.spring.SpringComponent;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.LongPredicate;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The cold tier (docs/architecture.md, "Archive"). Settled events move from the indexed {@code event}
 * table into immutable segments of archive format 2 ({@link Segments}), up to
 * {@code archive.segment-events} at a time; reads merge both tiers, so lookups, rollbacks and purges
 * behave the same whichever tier holds an event. A read parses each candidate's header, skips segments
 * and units that cannot match, fetches the selected units by byte range and matches the filter's
 * column rules on their columns ({@link Segments#match}); only when a row matched does it get the
 * segment's ids (cache or shared frames) and apply the id rules, and it builds rows for the survivors
 * only. Both steps use the SQL's predicate ({@link EventFilter#matchesColumns},
 * {@link EventFilter#matchesId}). Rolled-back flags
 * live beside the segments ({@code segment_flag}); a purge deletes or rewrites whole segments. The
 * {@link SegmentIndex} is changed in every transaction that writes or rewrites a segment, and a read it
 * narrows asks only the segments it names. Every method but {@link #compact}, {@link #totals} and
 * {@link #catchUpIndex} runs on the caller's connection and transaction.
 */
@SpringComponent
@RequiredArgsConstructor
public final class Archive {

    /** Segments read per candidate query. */
    public static final int SEGMENT_PAGE = 32;

    /** A segment budget for {@link #count(Connection, Shard, EventFilter, int)} that counts every candidate. */
    public static final int ALL_SEGMENTS = Integer.MAX_VALUE;

    /** Compresses frames on the calling thread: no burst of cores on a game server. */
    public static final Executor CALLING_THREAD = Runnable::run;

    private final @NotNull EventRepository eventRepository;
    private final @NotNull SegmentRepository segmentRepository;
    private final @NotNull SegmentCache cache;
    private final @NotNull SegmentIndex segmentIndex;

    /**
     * One packing pass, in one transaction: takes the {@code segment-events} oldest hot events that are
     * older than {@code settle-seconds} and packs them as one segment, when there are that many or when
     * the oldest is older than {@code after-hours}. Run passes until one returns 0.
     *
     * @param shard     the shard
     * @param settings  the archive settings
     * @param nowMillis the current time in Unix milliseconds
     * @return the number of events packed; 0 when there is nothing to do
     * @throws SQLException when reading or writing fails (the transaction is rolled back)
     */
    public int compact(@NotNull Shard shard, @NotNull ArchiveSettings settings, long nowMillis) throws SQLException {
        Database database = shard.database();
        Sql sql = database.sql();
        int limit = settings.segmentEvents();
        long belowId = EventIds.floor(nowMillis - Duration.ofSeconds(settings.settleSeconds()).toMillis());
        long oldBound = EventIds.floor(nowMillis - Duration.ofHours(settings.afterHours()).toMillis());
        boolean due = database.read(connection -> {
            long oldest = eventRepository.oldestId(connection, sql);
            if (oldest == 0 || oldest >= belowId) {
                return false;
            }
            return oldest < oldBound || eventRepository.hasAtLeast(connection, sql, belowId, limit);
        });
        if (!due) {
            return 0;
        }
        return database.transaction(connection -> {
            List<EventRow> rows = eventRepository.oldestForArchive(connection, sql, belowId, limit);
            if (rows.isEmpty() || rows.size() < limit && rows.getFirst().id() >= oldBound) {
                return 0;
            }
            pack(connection, shard, rows, CALLING_THREAD);
            eventRepository.deleteIds(connection, sql, rows.stream().map(EventRow::id).toList());
            return rows.size();
        });
    }

    /**
     * Writes rows as one segment without touching the hot table (packing, bulk imports). The rows'
     * rolled-back flags go to the segment's flag row.
     *
     * @param connection a connection inside a transaction
     * @param shard      the shard
     * @param rows       the rows, in any order, with distinct ids
     * @param executor   where the segment's frames are compressed
     * @return the new segment's id
     * @throws SQLException when a world is unknown or writing fails
     * @throws IllegalArgumentException when the rows are empty or an id repeats
     */
    public long pack(@NotNull Connection connection, @NotNull Shard shard, @NotNull List<EventRow> rows,
            @NotNull Executor executor) throws SQLException {
        Sql sql = shard.database().sql();
        PackedSegment segment = Segments.encode(rows, worldOrder(connection, shard, rows), executor);
        long id = segmentRepository.insert(connection, sql, segment);
        segmentIndex.added(connection, shard, places(id, rows));
        return id;
    }

    /**
     * Indexes some of the segments written before the segment index existed (or after it was emptied),
     * newest first, in transactions of {@link SegmentIndex#CATCH_UP_BATCH}.
     *
     * @param shard       the shard
     * @param maxSegments the most segments to index
     * @return how many were indexed and whether more are missing
     * @throws SQLException when reading or writing fails
     */
    public @NotNull SegmentIndex.CatchUp catchUpIndex(@NotNull Shard shard, int maxSegments) throws SQLException {
        return segmentIndex.catchUp(shard, maxSegments);
    }

    /**
     * Archived events matching a filter, in reading order beyond a cursor. Reads segments in order and
     * stops once no further segment can hold one of the {@code limit} best events, counting the hot
     * events already found.
     *
     * @param connection  a connection
     * @param shard       the shard
     * @param filter      the filter
     * @param cursor      the exclusive keyset bound, 0 for none
     * @param limit       the most events wanted in total
     * @param newestFirst the order
     * @param hot         the hot-tier events already found for the same page
     * @return the archived events (possibly more than needed; the caller merges and trims)
     * @throws SQLException when reading fails
     */
    public @NotNull List<EventRow> find(@NotNull Connection connection, @NotNull Shard shard,
            @NotNull EventFilter filter, long cursor, int limit, boolean newestFirst, @NotNull List<EventRow> hot)
            throws SQLException {
        Comparator<Long> worstFirst = newestFirst ? Comparator.naturalOrder() : Comparator.reverseOrder();
        PriorityQueue<Long> best = new PriorityQueue<>(worstFirst);
        hot.forEach(row -> keep(best, row.id(), limit));
        List<EventRow> found = new ArrayList<>();
        SegmentPages pages = pages(connection, shard, filter, cursor, newestFirst);
        while (true) {
            List<SegmentRow> page = pages.next();
            if (page.isEmpty()) {
                return found;
            }
            for (SegmentRow segment : page) {
                if (best.size() >= limit && cannotImprove(segment, best.peek(), newestFirst)) {
                    return found;
                }
                SegmentMatch match = match(connection, shard, segment, filter);
                if (match.count() == 0) {
                    continue;
                }
                SegmentHeader header = header(shard, segment);
                SharedColumns shared = shared(connection, shard, segment, header);
                for (EventRow row : match.rows(shared, idRule(segment, header, filter, cursor, newestFirst))) {
                    found.add(row);
                    keep(best, row.id(), limit);
                }
            }
        }
    }

    /**
     * Counts archived events matching a filter, every candidate segment.
     *
     * @param connection a connection
     * @param shard      the shard
     * @param filter     the filter
     * @return the count
     * @throws SQLException when reading fails
     */
    public long count(@NotNull Connection connection, @NotNull Shard shard, @NotNull EventFilter filter)
            throws SQLException {
        return count(connection, shard, filter, ALL_SEGMENTS).total();
    }

    /**
     * Counts archived events matching a filter, newest segments first, reading the bodies of at most
     * {@code maxSegments} segments. A candidate whose header selects no unit costs no body read and
     * does not use the budget; once the budget is spent and another candidate would need its body, the
     * count stops and is a lower bound.
     *
     * @param connection  a connection
     * @param shard       the shard
     * @param filter      the filter
     * @param maxSegments the most segment bodies to read; {@link #ALL_SEGMENTS} for an exact count
     * @return the count, exact when every candidate was counted
     * @throws SQLException when reading fails
     */
    public @NotNull EventCount count(@NotNull Connection connection, @NotNull Shard shard,
            @NotNull EventFilter filter, int maxSegments) throws SQLException {
        long count = 0;
        int read = 0;
        SegmentPages pages = pages(connection, shard, filter, 0, true);
        while (true) {
            List<SegmentRow> page = pages.next();
            if (page.isEmpty()) {
                return EventCount.of(count);
            }
            for (SegmentRow segment : page) {
                if (header(shard, segment).units(filter).length == 0) {
                    continue;
                }
                if (read >= maxSegments) {
                    return EventCount.atLeast(count);
                }
                read++;
                count += count(connection, shard, segment, filter);
            }
        }
    }

    /**
     * Sets or clears the rolled-back flag of archived events. Only the segments' flag rows change; a
     * segment body is never rewritten for a flag.
     *
     * @param connection a connection inside a transaction
     * @param shard      the shard
     * @param ids        event ids (ids in the hot tier are ignored)
     * @param rolledBack the new value
     * @return the number of archived events whose flag changed
     * @throws SQLException when reading or writing fails
     */
    public int setRolledBack(@NotNull Connection connection, @NotNull Shard shard, @NotNull Collection<Long> ids,
            boolean rolledBack) throws SQLException {
        if (ids.isEmpty()) {
            return 0;
        }
        Sql sql = shard.database().sql();
        Set<Long> wanted = new HashSet<>(ids);
        long[] sorted = wanted.stream().mapToLong(Long::longValue).sorted().toArray();
        EventFilter range = EventFilter.everything(sorted[0], sorted[sorted.length - 1]);
        int changed = 0;
        SegmentRow after = null;
        while (true) {
            List<SegmentRow> page = segmentRepository.candidates(connection, sql, range, 0, after, SEGMENT_PAGE, false);
            for (SegmentRow segment : page) {
                after = segment;
                if (!anyWithin(sorted, segment.firstId(), segment.lastId())) {
                    continue;
                }
                Optional<int[]> stored = segmentRepository.flagsForUpdate(connection, sql, segment.id());
                if (stored.isEmpty()) {
                    continue;
                }
                long[] rowIds = shared(connection, shard, segment, header(shard, segment)).ids();
                BitSet flags = new BitSet(rowIds.length);
                Arrays.stream(stored.get()).forEach(flags::set);
                int here = 0;
                for (int ordinal = 0; ordinal < rowIds.length; ordinal++) {
                    if (flags.get(ordinal) != rolledBack && wanted.contains(rowIds[ordinal])) {
                        flags.set(ordinal, rolledBack);
                        here++;
                    }
                }
                if (here > 0) {
                    segmentRepository.writeFlags(connection, sql, segment.id(), flags.stream().toArray());
                    changed += here;
                }
            }
            if (page.size() < SEGMENT_PAGE) {
                return changed;
            }
        }
    }

    /**
     * Deletes archived events older than an id. A segment entirely older is deleted; a segment that
     * straddles the bound, or holds purged worlds next to others, is rewritten without the purged rows
     * (its flags kept) and the old one deleted.
     *
     * @param connection a connection inside a transaction
     * @param shard      the shard
     * @param belowId    the exclusive id bound
     * @param worlds     world ids to purge, or empty for every world
     * @param segments   the most segments to delete or rewrite in this call
     * @return the number of archived events deleted; 0 when nothing older is left
     * @throws SQLException when reading or writing fails
     */
    public int purge(@NotNull Connection connection, @NotNull Shard shard, long belowId,
            @NotNull Collection<Integer> worlds, int segments) throws SQLException {
        Sql sql = shard.database().sql();
        Set<Integer> purgedWorlds = Set.copyOf(worlds);
        EventFilter purged = new EventFilter(0, belowId - 1, purgedWorlds, List.of(), EventFilter.Bounds.NONE,
                Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), RollbackState.ANY);
        Predicate<EventRow> drop = row -> row.id() < belowId
                && (purgedWorlds.isEmpty() || purgedWorlds.contains(row.world()));
        int deleted = 0;
        int touched = 0;
        List<Long> whole = new ArrayList<>();
        SegmentRow after = null;
        while (touched < segments) {
            List<SegmentRow> page = segmentRepository.olderThan(connection, sql, belowId, after, SEGMENT_PAGE);
            for (SegmentRow segment : page) {
                if (touched >= segments) {
                    break;
                }
                after = segment;
                SegmentHeader header = header(shard, segment);
                if (!header.mayMatch(purged)) {
                    continue;
                }
                boolean onlyPurgedWorlds = purgedWorlds.isEmpty()
                        || Arrays.stream(header.worldIds()).allMatch(purgedWorlds::contains);
                if (segment.lastId() < belowId && onlyPurgedWorlds) {
                    whole.add(segment.id());
                    deleted += segment.events();
                    touched++;
                    continue;
                }
                if (!purgedWorlds.isEmpty() && count(connection, shard, segment, purged) == 0) {
                    continue;
                }
                int removed = rewrite(connection, shard, segment, header, drop);
                if (removed > 0) {
                    deleted += removed;
                    touched++;
                }
            }
            if (page.size() < SEGMENT_PAGE) {
                break;
            }
        }
        segmentRepository.delete(connection, sql, whole);
        whole.forEach(id -> cache.forget(shard.database(), id));
        if (deleted > 0) {
            segmentIndex.trim(connection, shard);
        }
        return deleted;
    }

    /**
     * How much the archive holds.
     *
     * @param shard the shard
     * @return events, segments and stored bytes
     * @throws SQLException when reading fails
     */
    public @NotNull SegmentTotals totals(@NotNull Shard shard) throws SQLException {
        return shard.database().read(connection -> segmentRepository.totals(connection, shard.database().sql()));
    }

    /**
     * The largest id the archive holds.
     *
     * @param connection a connection
     * @param sql        the shard's SQL context
     * @return the id, or 0 when the archive is empty
     * @throws SQLException when reading fails
     */
    public long newestId(@NotNull Connection connection, @NotNull Sql sql) throws SQLException {
        return segmentRepository.newestId(connection, sql);
    }

    /**
     * Rewrites a segment without some rows: decodes everything with its flags, encodes what is kept as a
     * new segment (flags included) and deletes the old one.
     *
     * @return the number of rows dropped (0 leaves the segment as it is)
     */
    private int rewrite(@NotNull Connection connection, @NotNull Shard shard, @NotNull SegmentRow segment,
            @NotNull SegmentHeader header, @NotNull Predicate<EventRow> drop) throws SQLException {
        Sql sql = shard.database().sql();
        Optional<int[]> flags = segmentRepository.flagsForUpdate(connection, sql, segment.id());
        if (flags.isEmpty()) {
            return 0;
        }
        SegmentRow locked = new SegmentRow(segment.id(), segment.firstId(), segment.lastId(), segment.events(),
                segment.header(), flags.get());
        List<EventRow> rows = decode(connection, shard, locked, header,
                IntStream.range(0, header.unitCount()).toArray());
        List<EventRow> kept = rows.stream().filter(drop.negate()).toList();
        int removed = rows.size() - kept.size();
        if (removed == 0) {
            return 0;
        }
        Optional<SegmentPlaces> replacement = Optional.empty();
        if (!kept.isEmpty()) {
            List<Integer> order = Arrays.stream(header.worldIds()).boxed().toList();
            long id = segmentRepository.insert(connection, sql, Segments.encode(kept, order, CALLING_THREAD));
            replacement = Optional.of(places(id, kept));
        }
        segmentRepository.delete(connection, sql, List.of(segment.id()));
        cache.forget(shard.database(), segment.id());
        segmentIndex.replaced(connection, shard, new RemovedSegment(segment.id(), header), replacement);
        return removed;
    }

    /**
     * The first step of a read: the rows of a segment whose columns pass the filter's column rules.
     * Fetches only the units the header selects (and the palette frame, when the filter needs it and
     * it is not cached), in one statement; computes no ids and builds no rows.
     */
    private @NotNull SegmentMatch match(@NotNull Connection connection, @NotNull Shard shard,
            @NotNull SegmentRow segment, @NotNull EventFilter filter) throws SQLException {
        SegmentHeader header = header(shard, segment);
        int[] units = header.units(filter);
        if (units.length == 0) {
            return Segments.match(header, filter, Optional.empty(), units, List.of(), segment.rolledBack());
        }
        Database database = shard.database();
        boolean needsPalettes = ColumnRules.needsPalettes(filter);
        Optional<Palettes> palettes = needsPalettes ? cache.palettes(database, segment.id()) : Optional.empty();
        boolean fetchPalettes = needsPalettes && palettes.isEmpty();
        List<ByteRange> ranges = new ArrayList<>(units.length + 1);
        for (int unit : units) {
            ranges.add(header.unit(unit));
        }
        if (fetchPalettes) {
            ranges.add(header.palettes());
        }
        List<byte[]> bytes = segmentRepository.readRanges(connection, database.sql(), segment.id(), ranges);
        if (fetchPalettes) {
            Palettes decoded = Segments.palettes(header, bytes.getLast());
            cache.keep(database, segment.id(), decoded);
            palettes = Optional.of(decoded);
            bytes = bytes.subList(0, units.length);
        }
        return Segments.match(header, filter, palettes, units, bytes, segment.rolledBack());
    }

    /**
     * How many events of a segment match a filter: column matches counted directly when the
     * segment's ids all pass the id rules, else with ids computed for the matches (never rows).
     */
    private long count(@NotNull Connection connection, @NotNull Shard shard, @NotNull SegmentRow segment,
            @NotNull EventFilter filter) throws SQLException {
        SegmentMatch match = match(connection, shard, segment, filter);
        if (match.count() == 0) {
            return 0;
        }
        SegmentHeader header = header(shard, segment);
        if (idsAllPass(segment, header, filter, 0, false)) {
            return match.count();
        }
        return match.count(shared(connection, shard, segment, header), idRule(segment, header, filter, 0, false));
    }

    /** The id rules for a segment's rows: none when every id of the segment passes them. */
    private static @NotNull LongPredicate idRule(@NotNull SegmentRow segment, @NotNull SegmentHeader header,
            @NotNull EventFilter filter, long cursor, boolean newestFirst) {
        if (idsAllPass(segment, header, filter, cursor, newestFirst)) {
            return id -> true;
        }
        return id -> filter.matchesId(id) && beyond(id, cursor, newestFirst);
    }

    private @NotNull List<EventRow> decode(@NotNull Connection connection, @NotNull Shard shard,
            @NotNull SegmentRow segment, @NotNull SegmentHeader header, int @NotNull [] units) throws SQLException {
        Database database = shard.database();
        Optional<SharedColumns> cached = cache.shared(database, segment.id());
        List<ByteRange> ranges = new ArrayList<>(units.length + 1);
        if (cached.isEmpty()) {
            ranges.add(header.shared());
        }
        for (int unit : units) {
            ranges.add(header.unit(unit));
        }
        List<byte[]> bytes = segmentRepository.readRanges(connection, database.sql(), segment.id(), ranges);
        SharedColumns shared;
        List<byte[]> frames;
        if (cached.isPresent()) {
            shared = cached.get();
            frames = bytes;
        } else {
            shared = Segments.shared(header, bytes.getFirst());
            cache.keep(database, segment.id(), shared);
            frames = bytes.subList(1, bytes.size());
        }
        return Segments.decodeUnits(header, shared, units, frames, segment.rolledBack());
    }

    private @NotNull SharedColumns shared(@NotNull Connection connection, @NotNull Shard shard,
            @NotNull SegmentRow segment, @NotNull SegmentHeader header) throws SQLException {
        Database database = shard.database();
        Optional<SharedColumns> cached = cache.shared(database, segment.id());
        if (cached.isPresent()) {
            return cached.get();
        }
        byte[] bytes = segmentRepository.readRanges(connection, database.sql(), segment.id(),
                List.of(header.shared())).getFirst();
        SharedColumns shared = Segments.shared(header, bytes);
        cache.keep(database, segment.id(), shared);
        return shared;
    }

    private @NotNull SegmentHeader header(@NotNull Shard shard, @NotNull SegmentRow segment) {
        return cache.header(shard.database(), segment.id(), segment.header());
    }

    /** The places of a new segment's rows, for the segment index. */
    private static @NotNull SegmentPlaces places(long segment, @NotNull List<EventRow> rows) {
        SegmentPlaces.Builder builder = SegmentPlaces.builder(segment);
        rows.forEach(row -> builder.add(row.world(), row.position()));
        return builder.build();
    }

    /**
     * The candidate segments of a read, page by page in reading order: those the segment index names when
     * it narrows the filter (their id ranges fetched first and ordered, then header and flags page by
     * page), else every segment whose id range overlaps the filter's, keyset-paged.
     */
    private @NotNull SegmentPages pages(@NotNull Connection connection, @NotNull Shard shard,
            @NotNull EventFilter filter, long cursor, boolean newestFirst) throws SQLException {
        Sql sql = shard.database().sql();
        Optional<IdRuns> planned = segmentIndex.plan(connection, shard, filter);
        if (planned.isEmpty()) {
            return new KeysetPages(connection, sql, filter, cursor, newestFirst);
        }
        Comparator<SegmentRange> order = newestFirst
                ? Comparator.comparingLong(SegmentRange::lastId).thenComparingLong(SegmentRange::id).reversed()
                : Comparator.comparingLong(SegmentRange::firstId).thenComparingLong(SegmentRange::id);
        List<Long> ids = segmentRepository.ranges(connection, sql, planned.get()).stream()
                .filter(range -> range.lastId() >= filter.minId() && range.firstId() <= filter.maxId())
                .filter(range -> cursor == 0 || (newestFirst ? range.firstId() < cursor : range.lastId() > cursor))
                .sorted(order).map(SegmentRange::id).toList();
        return new PlannedPages(connection, sql, ids);
    }

    /** Candidate segments, a page at a time; an empty page ends them. */
    private interface SegmentPages {
        @NotNull List<SegmentRow> next() throws SQLException;
    }

    /** Every segment whose id range overlaps the filter's, by the {@code last_id}/{@code first_id} keyset. */
    @RequiredArgsConstructor
    private final class KeysetPages implements SegmentPages {

        private final @NotNull Connection connection;
        private final @NotNull Sql sql;
        private final @NotNull EventFilter filter;
        private final long cursor;
        private final boolean newestFirst;
        private @Nullable SegmentRow after;
        private boolean done;

        @Override
        public @NotNull List<SegmentRow> next() throws SQLException {
            if (done) {
                return List.of();
            }
            List<SegmentRow> page = segmentRepository.candidates(connection, sql, filter, cursor, after, SEGMENT_PAGE,
                    newestFirst);
            done = page.size() < SEGMENT_PAGE;
            if (!page.isEmpty()) {
                after = page.getLast();
            }
            return page;
        }
    }

    /**
     * The segments the segment index named, in reading order: small first pages, so a lookup that stops
     * after a segment or two fetches few headers.
     */
    @RequiredArgsConstructor
    private final class PlannedPages implements SegmentPages {

        private final @NotNull Connection connection;
        private final @NotNull Sql sql;
        private final @NotNull List<Long> ids;
        private int next;
        private int size = 4;

        @Override
        public @NotNull List<SegmentRow> next() throws SQLException {
            while (next < ids.size()) {
                List<Long> page = ids.subList(next, Math.min(ids.size(), next + size));
                next += page.size();
                size = Math.min(SEGMENT_PAGE, size * 2);
                List<SegmentRow> rows = segmentRepository.rows(connection, sql, page);
                if (!rows.isEmpty()) {
                    return rows;
                }
            }
            return List.of();
        }
    }

    /**
     * The worlds of some rows in the order the writer ranks events in: by scope, then name.
     */
    private static @NotNull List<Integer> worldOrder(@NotNull Connection connection, @NotNull Shard shard,
            @NotNull List<EventRow> rows) throws SQLException {
        Set<Integer> ids = new HashSet<>();
        rows.forEach(row -> ids.add(row.world()));
        Map<Integer, WorldKey> keys = shard.dictionaries().worldKeys(connection, shard.database().sql(), ids);
        if (!keys.keySet().containsAll(ids)) {
            throw new SQLException("the world dictionary has no row for some of the worlds " + ids);
        }
        Comparator<Integer> byKey = Comparator.comparing((Integer id) -> keys.get(id).scope())
                .thenComparing(id -> keys.get(id).name());
        return ids.stream().sorted(byKey).toList();
    }

    private static boolean anyWithin(long @NotNull [] sorted, long from, long to) {
        int at = Arrays.binarySearch(sorted, from);
        int index = at >= 0 ? at : -at - 1;
        return index < sorted.length && sorted[index] <= to;
    }

    private static void keep(@NotNull PriorityQueue<Long> best, long id, int limit) {
        best.add(id);
        if (best.size() > limit) {
            best.poll();
        }
    }

    private static boolean cannotImprove(@NotNull SegmentRow segment, @Nullable Long worstKept, boolean newestFirst) {
        if (worstKept == null) {
            return false;
        }
        return newestFirst ? segment.lastId() < worstKept : segment.firstId() > worstKept;
    }

    /**
     * Whether every id of a segment passes the id rules: its id range inside the filter's, entirely
     * beyond the cursor, and its only node a listed one (or no nodes listed).
     */
    private static boolean idsAllPass(@NotNull SegmentRow segment, @NotNull SegmentHeader header,
            @NotNull EventFilter filter, long cursor, boolean newestFirst) {
        boolean inRange = segment.firstId() >= filter.minId() && segment.lastId() <= filter.maxId();
        boolean pastCursor = cursor == 0 || (newestFirst ? segment.lastId() < cursor : segment.firstId() > cursor);
        int[] nodes = header.nodeIds();
        boolean nodesPass = filter.nodes().isEmpty() || nodes.length == 1 && filter.nodes().contains(nodes[0]);
        return inRange && pastCursor && nodesPass;
    }

    private static boolean beyond(long id, long cursor, boolean newestFirst) {
        return cursor == 0 || (newestFirst ? id < cursor : id > cursor);
    }
}
