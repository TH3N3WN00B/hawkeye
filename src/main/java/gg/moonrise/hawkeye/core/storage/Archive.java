package gg.moonrise.hawkeye.core.storage;

import gg.moonrise.hawkeye.core.repository.EventFilter;
import gg.moonrise.hawkeye.core.repository.EventRepository;
import gg.moonrise.hawkeye.core.repository.EventRow;
import gg.moonrise.hawkeye.core.repository.SegmentCodec;
import gg.moonrise.hawkeye.core.repository.SegmentRepository;
import gg.moonrise.hawkeye.core.repository.SegmentRow;
import gg.moonrise.hawkeye.core.sql.Database;
import gg.moonrise.hawkeye.core.sql.Sql;
import gg.moonrise.hawkeye.util.BlockPositions;
import gg.moonrise.hawkeye.util.EventIds;
import gg.moonrise.moss.spring.SpringComponent;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The cold tier. Events older than {@code archive.after-hours} move from the indexed {@code event}
 * table into packed {@code segment}s, one per world region per pass ({@link SegmentCodec}); reads merge
 * both tiers, so lookups, rollbacks and purges behave the same whichever tier holds an event. Segments
 * are found by region, world, actor and id range and filtered with the same predicate as the SQL
 * ({@link EventFilter#matches}). Every method runs on the caller's connection and transaction.
 */
@SpringComponent
@RequiredArgsConstructor
public final class Archive {

    /** Segments read per candidate query. */
    public static final int SEGMENT_PAGE = 32;

    private final @NotNull EventRepository eventRepository;
    private final @NotNull SegmentRepository segmentRepository;

    /**
     * One archiving pass: moves up to {@code limit} of the oldest events below a time into segments,
     * in one transaction.
     *
     * @param shard        the shard
     * @param beforeMillis events older than this are archived
     * @param limit        the most events this pass
     * @return the number of events archived; fewer than {@code limit} means nothing older is left
     * @throws SQLException when reading or writing fails (the transaction is rolled back)
     */
    public int compact(@NotNull Shard shard, long beforeMillis, int limit) throws SQLException {
        Database database = shard.database();
        Sql sql = database.sql();
        long belowId = EventIds.floor(beforeMillis);
        return database.transaction(connection -> {
            List<EventRow> rows = eventRepository.oldestForArchive(connection, sql, belowId, limit);
            if (rows.isEmpty()) {
                return 0;
            }
            Map<RegionKey, List<EventRow>> groups = new LinkedHashMap<>();
            for (EventRow row : rows) {
                groups.computeIfAbsent(new RegionKey(row.world(), BlockPositions.regionKey(row.position())),
                        key -> new ArrayList<>()).add(row);
            }
            for (Map.Entry<RegionKey, List<EventRow>> group : groups.entrySet()) {
                insert(connection, sql, group.getKey().world(), group.getKey().region(), group.getValue());
            }
            eventRepository.deleteIds(connection, sql, rows.stream().map(EventRow::id).toList());
            return rows.size();
        });
    }

    /**
     * Archived events matching a filter, in reading order beyond a cursor. Reads segments in order and
     * stops once no further segment can hold one of the {@code limit} best events, counting the hot
     * events already found.
     *
     * @param connection  a connection
     * @param sql         the shard's SQL context
     * @param filter      the filter
     * @param cursor      the exclusive keyset bound, 0 for none
     * @param limit       the most events wanted in total
     * @param newestFirst the order
     * @param hot         the hot-tier events already found for the same page
     * @return the archived events (possibly more than needed; the caller merges and trims)
     * @throws SQLException when reading fails
     */
    public @NotNull List<EventRow> find(@NotNull Connection connection, @NotNull Sql sql, @NotNull EventFilter filter,
            long cursor, int limit, boolean newestFirst, @NotNull List<EventRow> hot) throws SQLException {
        Comparator<Long> worstFirst = newestFirst ? Comparator.naturalOrder() : Comparator.reverseOrder();
        PriorityQueue<Long> best = new PriorityQueue<>(worstFirst);
        hot.forEach(row -> keep(best, row.id(), limit));
        List<EventRow> found = new ArrayList<>();
        SegmentRow after = null;
        while (true) {
            List<SegmentRow> page = segmentRepository.candidates(connection, sql, filter, cursor, after, SEGMENT_PAGE,
                    newestFirst);
            for (SegmentRow segment : page) {
                if (best.size() >= limit && cannotImprove(segment, best.peek(), newestFirst)) {
                    return found;
                }
                for (EventRow row : SegmentCodec.decode(segment.body(), segment.world(), segment.region())) {
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
     * @param sql        the shard's SQL context
     * @param filter     the filter
     * @return the count
     * @throws SQLException when reading fails
     */
    public long count(@NotNull Connection connection, @NotNull Sql sql, @NotNull EventFilter filter)
            throws SQLException {
        long count = 0;
        SegmentRow after = null;
        while (true) {
            List<SegmentRow> page = segmentRepository.candidates(connection, sql, filter, 0, after, SEGMENT_PAGE, false);
            for (SegmentRow segment : page) {
                for (EventRow row : SegmentCodec.decode(segment.body(), segment.world(), segment.region())) {
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
     * Sets or clears the rolled-back flag of archived events by rewriting the segments that hold them.
     *
     * @param connection a connection inside a transaction
     * @param sql        the shard's SQL context
     * @param ids        event ids (ids in the hot tier are ignored)
     * @param rolledBack the new value
     * @return the number of archived events changed
     * @throws SQLException when reading or writing fails
     */
    public int setRolledBack(@NotNull Connection connection, @NotNull Sql sql, @NotNull Collection<Long> ids,
            boolean rolledBack) throws SQLException {
        if (ids.isEmpty()) {
            return 0;
        }
        Set<Long> wanted = new HashSet<>(ids);
        long min = wanted.stream().mapToLong(Long::longValue).min().orElseThrow();
        long max = wanted.stream().mapToLong(Long::longValue).max().orElseThrow();
        EventFilter range = EventFilter.everything(min, max);
        int changed = 0;
        SegmentRow after = null;
        while (true) {
            List<SegmentRow> page = segmentRepository.candidates(connection, sql, range, 0, after, SEGMENT_PAGE, false);
            for (SegmentRow segment : page) {
                List<EventRow> rows = SegmentCodec.decode(segment.body(), segment.world(), segment.region());
                List<EventRow> updated = new ArrayList<>(rows.size());
                int here = 0;
                for (EventRow row : rows) {
                    if (wanted.contains(row.id()) && row.rolledBack() != rolledBack) {
                        updated.add(withFlag(row, rolledBack));
                        here++;
                    } else {
                        updated.add(row);
                    }
                }
                if (here > 0) {
                    segmentRepository.update(connection, sql, segment(segment.id(), segment.world(), segment.region(),
                            updated));
                    changed += here;
                }
                after = segment;
            }
            if (page.size() < SEGMENT_PAGE) {
                return changed;
            }
        }
    }

    /**
     * Deletes archived events older than an id: whole segments when they are entirely older,
     * otherwise their older part.
     *
     * @param connection a connection inside a transaction
     * @param sql        the shard's SQL context
     * @param belowId    the exclusive id bound
     * @param worlds     world ids to purge, or empty for every world
     * @param segments   the most segments to process in this call
     * @return the number of archived events deleted
     * @throws SQLException when reading or writing fails
     */
    public int purge(@NotNull Connection connection, @NotNull Sql sql, long belowId,
            @NotNull Collection<Integer> worlds, int segments) throws SQLException {
        int deleted = 0;
        List<Long> whole = new ArrayList<>();
        for (SegmentRow segment : segmentRepository.olderThan(connection, sql, belowId, worlds, segments)) {
            if (segment.lastId() < belowId) {
                whole.add(segment.id());
                deleted += segment.events();
                continue;
            }
            List<EventRow> kept = SegmentCodec.decode(segment.body(), segment.world(), segment.region()).stream()
                    .filter(row -> row.id() >= belowId).toList();
            deleted += segment.events() - kept.size();
            segmentRepository.update(connection, sql, segment(segment.id(), segment.world(), segment.region(), kept));
        }
        segmentRepository.delete(connection, sql, whole);
        return deleted;
    }

    /**
     * How much the archive holds.
     *
     * @param shard the shard
     * @return {@code [events, segments]}
     * @throws SQLException when reading fails
     */
    public long @NotNull [] totals(@NotNull Shard shard) throws SQLException {
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

    private void insert(@NotNull Connection connection, @NotNull Sql sql, int world, long region,
            @NotNull List<EventRow> rows) throws SQLException {
        Set<Integer> actors = new HashSet<>();
        rows.forEach(row -> actors.add(row.actor()));
        segmentRepository.insert(connection, sql, segment(0, world, region, rows), actors);
    }

    private static @NotNull SegmentRow segment(long id, int world, long region, @NotNull List<EventRow> rows) {
        return new SegmentRow(id, world, region, rows.getFirst().id(), rows.getLast().id(), rows.size(),
                SegmentCodec.encode(rows, region));
    }

    private static @NotNull EventRow withFlag(@NotNull EventRow row, boolean rolledBack) {
        int flags = rolledBack ? row.flags() | EventRow.ROLLED_BACK : row.flags() & ~EventRow.ROLLED_BACK;
        return new EventRow(row.id(), row.world(), row.position(), row.actor(), row.action(), row.subject(),
                row.secondary(), row.amount(), flags, row.data());
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

    private record RegionKey(int world, long region) {
    }
}
