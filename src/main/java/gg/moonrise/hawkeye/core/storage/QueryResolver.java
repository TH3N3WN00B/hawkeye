package gg.moonrise.hawkeye.core.storage;

import gg.moonrise.hawkeye.core.repository.ActorRepository;
import gg.moonrise.hawkeye.core.repository.DictionaryRepository;
import gg.moonrise.hawkeye.core.repository.EventFilter;
import gg.moonrise.hawkeye.core.sql.Sql;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.query.Area;
import gg.moonrise.hawkeye.entity.query.LookupQuery;
import gg.moonrise.hawkeye.entity.server.WorldKey;
import gg.moonrise.hawkeye.util.BlockPositions;
import gg.moonrise.moss.spring.SpringComponent;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

/**
 * Turns a {@link LookupQuery} into an {@link EventFilter} for one shard by resolving names to that
 * shard's dictionary ids. A filter that names something the shard has never seen (an unknown player,
 * world or block) can match nothing, which is reported as an empty result rather than an empty
 * filter, so it never degrades into "match everything".
 */
@SpringComponent
@RequiredArgsConstructor
public final class QueryResolver {

    /** Chunk columns up to which a box gets one index range per column. */
    public static final int MAX_COLUMNS = 64;

    private final @NotNull DictionaryRepository dictionaryRepository;
    private final @NotNull ActorRepository actorRepository;

    /**
     * Resolves a query.
     *
     * @param connection a connection to the shard
     * @param shard      the shard
     * @param query      the query
     * @param scope      which of the shard's worlds to read
     * @return the filter, or empty when the query cannot match anything in this shard
     * @throws SQLException when a dictionary read fails
     */
    public @NotNull Optional<EventFilter> resolve(@NotNull Connection connection, @NotNull Shard shard,
            @NotNull LookupQuery query, @NotNull ShardScope scope) throws SQLException {
        Sql sql = shard.database().sql();
        Collection<String> scopes = switch (scope) {
            case ShardScope.Scopes scopeSet -> scopeSet.scopes();
            case ShardScope.All all -> Set.of();
        };
        if (scope instanceof ShardScope.Scopes scopeSet && scopeSet.scopes().isEmpty()) {
            return Optional.empty();
        }
        Map<Integer, WorldKey> worlds = shard.dictionaries().worlds(connection, sql, scopes);

        Set<Integer> worldIds = new HashSet<>();
        List<EventFilter.WorldRange> positions = new ArrayList<>();
        EventFilter.Bounds bounds = EventFilter.Bounds.NONE;
        switch (query.area()) {
            case Area.Everywhere everywhere -> {
                if (scope instanceof ShardScope.Scopes) {
                    if (worlds.isEmpty()) {
                        return Optional.empty();
                    }
                    worldIds.addAll(worlds.keySet());
                }
            }
            case Area.World world -> {
                worldIds.addAll(named(worlds, world.world()));
                if (worldIds.isEmpty()) {
                    return Optional.empty();
                }
            }
            case Area.Box box -> {
                Set<Integer> ids = named(worlds, box.world());
                if (ids.isEmpty()) {
                    return Optional.empty();
                }
                for (int id : ids) {
                    positions.addAll(columns(id, box));
                }
                bounds = new EventFilter.Bounds(Math.max(box.minY(), BlockPositions.MIN_Y),
                        Math.min(box.maxY(), BlockPositions.MAX_Y), clampHorizontal(box.minZ()),
                        clampHorizontal(box.maxZ()));
            }
            case Area.Block block -> {
                Set<Integer> ids = named(worlds, block.world());
                if (ids.isEmpty()) {
                    return Optional.empty();
                }
                long position = BlockPositions.pack(block.x(), block.y(), block.z());
                ids.forEach(id -> positions.add(EventFilter.WorldRange.of(id, position, position)));
            }
        }

        Set<Integer> actors = actorIds(connection, sql, query.actors());
        if (!query.actors().isEmpty() && actors.isEmpty()) {
            return Optional.empty();
        }
        Set<Integer> types = typeIds(connection, sql, query.includedTypes());
        if (!query.includedTypes().isEmpty() && types.isEmpty()) {
            return Optional.empty();
        }
        Set<Integer> actions = query.actions().stream().map(Action::code).collect(Collectors.toSet());
        return Optional.of(new EventFilter(query.time().minId(), query.time().maxId(), worldIds, positions, bounds,
                Set.of(), actors, actorIds(connection, sql, query.excludedActors()), actions, types,
                typeIds(connection, sql, query.excludedTypes()), query.rollbackState()));
    }

    private static @NotNull Set<Integer> named(@NotNull Map<Integer, WorldKey> worlds, @NotNull String name) {
        return worlds.entrySet().stream()
                .filter(entry -> entry.getValue().name().equals(name))
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    /**
     * The position ranges of a box: one per chunk column, each trimmed to the box's local x at the two
     * edge columns. A box wider than {@link #MAX_COLUMNS} chunk columns becomes three ranges instead
     * (the two trimmed edges and one range over every column between them), which reads more index
     * entries per column but keeps the statement small; the residual z bound keeps it exact.
     */
    private static @NotNull List<EventFilter.WorldRange> columns(int world, @NotNull Area.Box box) {
        int minX = clampHorizontal(box.minX());
        int maxX = clampHorizontal(box.maxX());
        int minZ = clampHorizontal(box.minZ());
        int maxZ = clampHorizontal(box.maxZ());
        int minChunkX = minX >> 4;
        int maxChunkX = maxX >> 4;
        List<EventFilter.WorldRange> ranges = new ArrayList<>();
        if (maxChunkX - minChunkX + 1 > MAX_COLUMNS) {
            ranges.add(column(world, minChunkX, minZ, maxZ, minX & 15, 15));
            List<BlockPositions.Range> inner = BlockPositions.columnRanges(minChunkX + 1, minZ >> 4, maxChunkX - 1,
                    maxZ >> 4);
            ranges.add(EventFilter.WorldRange.of(world, inner.getFirst().from(), inner.getLast().to()));
            ranges.add(column(world, maxChunkX, minZ, maxZ, 0, maxX & 15));
            return ranges;
        }
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            int minLocal = chunkX == minChunkX ? minX & 15 : 0;
            int maxLocal = chunkX == maxChunkX ? maxX & 15 : 15;
            ranges.add(column(world, chunkX, minZ, maxZ, minLocal, maxLocal));
        }
        return ranges;
    }

    private static @NotNull EventFilter.WorldRange column(int world, int chunkX, int minZ, int maxZ, int minLocalX,
            int maxLocalX) {
        BlockPositions.Range range = BlockPositions.columnRanges(chunkX, minZ >> 4, chunkX, maxZ >> 4).getFirst();
        return new EventFilter.WorldRange(world, range.from(), range.to(), minLocalX, maxLocalX);
    }

    private static int clampHorizontal(int value) {
        return Math.clamp(value, BlockPositions.MIN_HORIZONTAL, BlockPositions.MAX_HORIZONTAL);
    }

    private @NotNull Set<Integer> actorIds(@NotNull Connection connection, @NotNull Sql sql,
            @NotNull Set<String> names) throws SQLException {
        if (names.isEmpty()) {
            return Set.of();
        }
        return actorRepository.byNames(connection, sql, names).keySet();
    }

    private @NotNull Set<Integer> typeIds(@NotNull Connection connection, @NotNull Sql sql,
            @NotNull Set<String> keys) throws SQLException {
        Set<Integer> ids = new HashSet<>();
        for (String key : keys) {
            ids.addAll(dictionaryRepository.typeIdsOf(connection, sql, key));
        }
        return ids;
    }
}
