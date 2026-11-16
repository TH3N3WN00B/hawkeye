package gg.moonrise.hawkeye.core.storage;

import gg.moonrise.hawkeye.config.file.ArchiveSettings;
import gg.moonrise.hawkeye.core.repository.EventFilter;
import gg.moonrise.hawkeye.core.repository.EventRepository;
import gg.moonrise.hawkeye.core.repository.EventRow;
import gg.moonrise.hawkeye.core.repository.SegmentRepository;
import gg.moonrise.hawkeye.core.repository.SegmentRow;
import gg.moonrise.hawkeye.core.repository.SegmentTotals;
import gg.moonrise.hawkeye.core.repository.segment.ByteRange;
import gg.moonrise.hawkeye.core.repository.segment.PackedSegment;
import gg.moonrise.hawkeye.core.repository.segment.SegmentHeader;
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
 * and units that cannot match, fetches the shared frames and the matching units by byte range and
 * filters the rows with the same predicate as the SQL ({@link EventFilter#matches}). Rolled-back flags
 * live beside the segments ({@code segment_flag}); a purge deletes or rewrites whole segments. Every
 * method but {@link #compact} and {@link #totals} runs on the caller's connection and transaction.
 */
@SpringComponent
@RequiredArgsConstructor
public final class Archive {

    /** Segments read per candidate query. */
    public static final int SEGMENT_PAGE = 32;

    /** Compresses frames on the calling thread: no burst of cores on a game server. */
    public static final Executor CALLING_THREAD = Runnable::run;

    private final @NotNull EventRepository eventRepository;
    private final @NotNull SegmentRepository segmentRepository;
    private final @NotNull SegmentCache cache;

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
        return segmentRepository.insert(connection, sql, segment);
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
        SegmentRow after = null;
        while (true) {
            List<SegmentRow> page = segmentRepository.candidates(connection, shard.database().sql(), filter, cursor,
                    after, SEGMENT_PAGE, newestFirst);
            for (SegmentRow segment : page) {
                if (best.size() >= limit && cannotImprove(segment, best.peek(), newestFirst)) {
                    return found;
                }
                for (EventRow row : read(connection, shard, segment, filter)) {
                    if (beyond(row.id(), cursor, newestFirst) && filter.matches(row)) {
                        found.add(row);
                        keep(best, row.id(), limit);
                    }
                }
                after = segment;
            }
            if (page.size() < SEGMENT_PAGE) {
                return found;
            }
        }
    }

    /**
     * Counts archived events matching a filter.
     *
     * @param connection a connection
     * @param shard      the shard
     * @param filter     the filter
     * @return the count
     * @throws SQLException when reading fails
     */
    public long count(@NotNull Connection connection, @NotNull Shard shard, @NotNull EventFilter filter)
            throws SQLException {
        long count = 0;
        SegmentRow after = null;
        while (true) {
            List<SegmentRow> page = segmentRepository.candidates(connection, shard.database().sql(), filter, 0, after,
                    SEGMENT_PAGE, false);
            for (SegmentRow segment : page) {
                for (EventRow row : read(connection, shard, segment, filter)) {
                    if (filter.matches(row)) {
                        count++;
                    }
                }
                after = segment;
            }
            if (page.size() < SEGMENT_PAGE) {
                return count;
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
                if (!purgedWorlds.isEmpty() && read(connection, shard, segment, purged).stream().noneMatch(drop)) {
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
        if (!kept.isEmpty()) {
            List<Integer> order = Arrays.stream(header.worldIds()).boxed().toList();
            segmentRepository.insert(connection, sql, Segments.encode(kept, order, CALLING_THREAD));
        }
        segmentRepository.delete(connection, sql, List.of(segment.id()));
        cache.forget(shard.database(), segment.id());
        return removed;
    }

    /** The rows of the units of a segment that may match a filter, with flags; empty when none may. */
    private @NotNull List<EventRow> read(@NotNull Connection connection, @NotNull Shard shard,
            @NotNull SegmentRow segment, @NotNull EventFilter filter) throws SQLException {
        SegmentHeader header = header(shard, segment);
        int[] units = header.units(filter);
        return units.length == 0 ? List.of() : decode(connection, shard, segment, header, units);
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

    private static boolean beyond(long id, long cursor, boolean newestFirst) {
        return cursor == 0 || (newestFirst ? id < cursor : id > cursor);
    }
}
