package gg.moonrise.hawkeye.core.storage;

import gg.moonrise.hawkeye.core.repository.ActorIndexRepository;
import gg.moonrise.hawkeye.core.repository.EventFilter;
import gg.moonrise.hawkeye.core.repository.place.IdRuns;
import gg.moonrise.hawkeye.core.sql.Sql;
import gg.moonrise.moss.spring.SpringComponent;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

/**
 * The actor part of the {@link SegmentIndex} (docs/architecture.md, "Segment index"): per actor id, the
 * ids of the segments that hold rows of that actor ({@code actor_segment}). A lookup by a few players
 * without a place reads only their segments instead of testing every segment's header. The caller holds
 * the state lock for every write.
 */
@SpringComponent
@RequiredArgsConstructor
public final class ActorIndex {

    /** A filter listing more actors than this is not narrowed by the actor index. */
    public static final int MAX_ACTORS = 64;

    private final @NotNull ActorIndexRepository actors;

    /**
     * Whether the actor index narrows a filter: it lists 1 to {@link #MAX_ACTORS} actors (excluded
     * actors never narrow).
     *
     * @param filter the filter
     * @return {@code true} when {@link #plan} applies
     */
    public boolean applies(@NotNull EventFilter filter) {
        return !filter.actors().isEmpty() && filter.actors().size() <= MAX_ACTORS;
    }

    /**
     * Records segments just written and a segment rewritten: the removed id leaves the rows of the old
     * header's actors, the new ids join the rows of their actors, ids below the oldest stored segment
     * are dropped from every row rewritten, and a row left empty is deleted.
     *
     * @param connection a connection inside the writing transaction
     * @param sql        the shard's SQL context
     * @param added      the new segments
     * @param removal    the rewritten segment, already deleted, if any
     * @param oldest     the oldest stored segment id (0 when none is stored)
     * @throws SQLException when reading or writing fails
     */
    public void apply(@NotNull Connection connection, @NotNull Sql sql, @NotNull List<SegmentContent> added,
            @NotNull Optional<RemovedSegment> removal, long oldest) throws SQLException {
        TreeSet<Integer> touched = new TreeSet<>();
        for (SegmentContent content : added) {
            for (int actor : content.actors()) {
                touched.add(actor);
            }
        }
        removal.ifPresent(gone -> {
            for (int actor : gone.header().actorIds()) {
                touched.add(actor);
            }
        });
        if (touched.isEmpty()) {
            return;
        }
        Map<Integer, IdRuns> rows = new TreeMap<>();
        actors.read(connection, sql, touched).forEach((actor, body) -> rows.put(actor, IdRuns.fromBytes(body)));
        touched.forEach(actor -> rows.computeIfAbsent(actor, ignored -> new IdRuns()));
        removal.ifPresent(gone -> {
            for (int actor : gone.header().actorIds()) {
                rows.get(actor).remove(gone.segment());
            }
        });
        for (SegmentContent content : added) {
            for (int actor : content.actors()) {
                rows.get(actor).add(content.segment());
            }
        }
        List<ActorIndexRepository.Row> written = new ArrayList<>(rows.size());
        List<Integer> empty = new ArrayList<>();
        rows.forEach((actor, ids) -> {
            ids.dropBelow(oldest);
            if (ids.isEmpty()) {
                empty.add(actor);
            } else {
                written.add(new ActorIndexRepository.Row(actor, ids.max(), ids.toBytes()));
            }
        });
        actors.delete(connection, sql, empty);
        actors.write(connection, sql, written);
    }

    /**
     * Deletes the rows that name no stored segment any more (after a purge).
     *
     * @param connection a connection inside the purging transaction
     * @param sql        the shard's SQL context
     * @param oldest     the oldest stored segment id, or {@link Long#MAX_VALUE} when none is stored
     * @return the number of rows deleted
     * @throws SQLException when writing fails
     */
    public int trim(@NotNull Connection connection, @NotNull Sql sql, long oldest) throws SQLException {
        return actors.deleteOlderThan(connection, sql, oldest);
    }

    /**
     * The indexed segments that hold rows of a filter's listed actors: the union of their rows (an
     * actor without a row contributes nothing).
     *
     * @param connection a connection
     * @param sql        the shard's SQL context
     * @param filter     a filter the index {@linkplain #applies applies} to
     * @return the segment ids (some may no longer exist)
     * @throws SQLException when reading fails
     */
    public @NotNull IdRuns plan(@NotNull Connection connection, @NotNull Sql sql, @NotNull EventFilter filter)
            throws SQLException {
        IdRuns out = new IdRuns();
        for (byte[] body : actors.read(connection, sql, new TreeSet<>(filter.actors())).values()) {
            out.addAll(IdRuns.fromBytes(body));
        }
        return out;
    }
}
