package gg.moonrise.hawkeye.core.storage;

import gg.moonrise.hawkeye.core.repository.EventFilter;
import gg.moonrise.hawkeye.core.repository.EventRepository;
import gg.moonrise.hawkeye.core.repository.EventRow;
import gg.moonrise.hawkeye.core.sql.Database;
import gg.moonrise.hawkeye.core.sql.Sql;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.hawkeye.entity.event.PendingEvent;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.entity.query.LookupQuery;
import gg.moonrise.hawkeye.entity.server.ServerNode;
import gg.moonrise.hawkeye.entity.server.WorldKey;
import gg.moonrise.hawkeye.util.EventIds;
import gg.moonrise.moss.spring.SpringComponent;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

/**
 * The event operations the services and the writer use, on any shard: writing a batch, reading
 * pages, counting, flagging and purging. Owns all connection and transaction handling around the
 * repositories, so no service touches SQL.
 */
@SpringComponent
@RequiredArgsConstructor
public final class EventStore {

    private final @NotNull EventRepository eventRepository;
    private final @NotNull QueryResolver queryResolver;

    /**
     * Writes a batch: dictionary values are interned first in auto-commit mode (so an id never refers
     * to a rolled-back row), then every event is inserted in one transaction. Idempotent: retrying a
     * batch inserts nothing twice.
     *
     * @param home   the home shard
     * @param node   this server's registration
     * @param scope  this server's world scope
     * @param events the batch
     * @return the number of rows inserted
     * @throws SQLException when interning or inserting fails
     */
    public int write(@NotNull Shard home, @NotNull ServerNode node, @NotNull String scope,
            @NotNull List<PendingEvent> events) throws SQLException {
        if (events.isEmpty()) {
            return 0;
        }
        Database database = home.database();
        Sql sql = database.sql();
        List<EventRow> rows = database.read(connection -> rows(connection, home, node, scope, events));
        return database.transaction(connection -> eventRepository.insert(connection, sql, rows));
    }

    /**
     * Reads a page of events.
     *
     * @param shard       the shard
     * @param query       the query
     * @param scope       which of the shard's worlds
     * @param cursor      the exclusive keyset bound from the previous page, 0 for the first
     * @param limit       the most events
     * @param newestFirst the order
     * @return the events in that order; empty when nothing matches
     * @throws SQLException when reading fails
     */
    public @NotNull List<StoredEvent> find(@NotNull Shard shard, @NotNull LookupQuery query,
            @NotNull ShardScope scope, long cursor, int limit, boolean newestFirst) throws SQLException {
        return shard.database().read(connection -> {
            Optional<EventFilter> filter = queryResolver.resolve(connection, shard, query, scope);
            if (filter.isEmpty()) {
                return List.of();
            }
            List<EventRow> rows = eventRepository.find(connection, shard.database().sql(), filter.get(), cursor, limit,
                    newestFirst);
            return stored(connection, shard, rows);
        });
    }

    /**
     * Counts matching events.
     *
     * @param shard the shard
     * @param query the query
     * @param scope which of the shard's worlds
     * @return the count
     * @throws SQLException when reading fails
     */
    public long count(@NotNull Shard shard, @NotNull LookupQuery query, @NotNull ShardScope scope)
            throws SQLException {
        return shard.database().read(connection -> {
            Optional<EventFilter> filter = queryResolver.resolve(connection, shard, query, scope);
            return filter.isEmpty() ? 0L : eventRepository.count(connection, shard.database().sql(), filter.get());
        });
    }

    /**
     * Sets or clears the rolled-back flag of events, in one transaction.
     *
     * @param shard      the shard
     * @param ids        the event ids
     * @param rolledBack the new value
     * @return the number of events updated
     * @throws SQLException when writing fails
     */
    public int setRolledBack(@NotNull Shard shard, @NotNull Collection<Long> ids, boolean rolledBack)
            throws SQLException {
        if (ids.isEmpty()) {
            return 0;
        }
        return shard.database().transaction(connection -> eventRepository.setRolledBack(connection,
                shard.database().sql(), ids, rolledBack));
    }

    /**
     * Deletes one batch of events older than a time.
     *
     * @param shard        the shard
     * @param beforeMillis events strictly older than this are deleted
     * @param worlds       world ids to purge, or empty for every world
     * @param limit        the most events in this batch
     * @return the number deleted; fewer than {@code limit} means the purge is complete
     * @throws SQLException when deleting fails
     */
    public int purgeBatch(@NotNull Shard shard, long beforeMillis, @NotNull Collection<Integer> worlds, int limit)
            throws SQLException {
        long belowId = EventIds.floor(beforeMillis);
        return shard.database().read(connection -> eventRepository.deleteBatch(connection, shard.database().sql(),
                belowId, worlds, limit));
    }

    /**
     * The worlds of some scopes in a shard (for purges by world).
     *
     * @param shard  the shard
     * @param scopes world scopes, or empty for all
     * @return world id to key
     * @throws SQLException when reading fails
     */
    public @NotNull Map<Integer, WorldKey> worlds(@NotNull Shard shard, @NotNull Collection<String> scopes)
            throws SQLException {
        return shard.database().read(connection -> shard.dictionaries().worlds(connection, shard.database().sql(),
                scopes));
    }

    /**
     * Every server registered in a shard.
     *
     * @param shard the shard
     * @return the servers by node id
     * @throws SQLException when reading fails
     */
    public @NotNull List<ServerNode> servers(@NotNull Shard shard) throws SQLException {
        return shard.database().read(connection -> shard.dictionaries().refreshServers(connection,
                shard.database().sql()));
    }

    private static @NotNull List<EventRow> rows(@NotNull Connection connection, @NotNull Shard home,
            @NotNull ServerNode node, @NotNull String scope, @NotNull List<PendingEvent> events) throws SQLException {
        Sql sql = home.database().sql();
        Dictionaries dictionaries = home.dictionaries();
        Set<String> worldNames = new HashSet<>();
        Map<String, Actor> actors = new HashMap<>();
        Set<String> types = new LinkedHashSet<>();
        for (PendingEvent event : events) {
            worldNames.add(event.world());
            actors.putIfAbsent(event.actor().key(), event.actor());
            if (!event.subject().isEmpty()) {
                types.add(event.subject());
            }
            if (event.secondary() != null) {
                types.add(event.secondary());
            }
        }
        Map<String, Integer> worldIds = dictionaries.internWorlds(connection, sql, scope, worldNames);
        Map<String, Integer> actorIds = dictionaries.internActors(connection, sql, actors.values());
        Map<String, Integer> typeIds = dictionaries.internTypes(connection, sql, types);
        List<EventRow> rows = new ArrayList<>(events.size());
        for (PendingEvent event : events) {
            rows.add(new EventRow(EventIds.fromStamp(event.stamp(), node.node()),
                    require(worldIds, event.world(), "world"),
                    event.position(),
                    require(actorIds, event.actor().key(), "actor"),
                    event.action().code(),
                    event.subject().isEmpty() ? 0 : require(typeIds, event.subject(), "type"),
                    event.secondary() == null ? 0 : require(typeIds, event.secondary(), "type"),
                    Math.max(0, event.amount()),
                    0,
                    event.data()));
        }
        return rows;
    }

    private static @NotNull List<StoredEvent> stored(@NotNull Connection connection, @NotNull Shard shard,
            @NotNull List<EventRow> rows) throws SQLException {
        if (rows.isEmpty()) {
            return List.of();
        }
        Sql sql = shard.database().sql();
        Dictionaries dictionaries = shard.dictionaries();
        Set<Integer> worldIds = new HashSet<>();
        Set<Integer> actorIds = new HashSet<>();
        Set<Integer> typeIds = new HashSet<>();
        Set<Integer> nodes = new HashSet<>();
        for (EventRow row : rows) {
            worldIds.add(row.world());
            actorIds.add(row.actor());
            nodes.add(EventIds.node(row.id()));
            if (row.subject() != 0) {
                typeIds.add(row.subject());
            }
            if (row.secondary() != 0) {
                typeIds.add(row.secondary());
            }
        }
        Map<Integer, WorldKey> worlds = dictionaries.worldKeys(connection, sql, worldIds);
        Map<Integer, Actor> actors = dictionaries.actors(connection, sql, actorIds);
        Map<Integer, String> types = dictionaries.typeNames(connection, sql, typeIds);
        Map<Integer, ServerNode> servers = dictionaries.servers(connection, sql, nodes);
        List<StoredEvent> out = new ArrayList<>(rows.size());
        for (EventRow row : rows) {
            Optional<Action> action = Action.byCode(row.action());
            if (action.isEmpty()) {
                continue;
            }
            WorldKey world = worlds.get(row.world());
            ServerNode server = servers.get(EventIds.node(row.id()));
            out.add(new StoredEvent(row.id(),
                    server == null ? "#" + EventIds.node(row.id()) : server.name(),
                    world == null ? "#" + row.world() : world.name(),
                    row.position(),
                    actors.getOrDefault(row.actor(), Actor.source("unknown")),
                    action.get(),
                    row.subject() == 0 ? "" : types.getOrDefault(row.subject(), "minecraft:air"),
                    row.secondary() == 0 ? null : types.getOrDefault(row.secondary(), "minecraft:air"),
                    row.amount(),
                    row.rolledBack(),
                    row.data()));
        }
        return out;
    }

    private static int require(@NotNull Map<String, Integer> ids, @NotNull String key, @NotNull String what)
            throws SQLException {
        Integer id = ids.get(key);
        if (id == null) {
            throw new SQLException("the " + what + " dictionary returned no id for " + key);
        }
        return id;
    }
}
