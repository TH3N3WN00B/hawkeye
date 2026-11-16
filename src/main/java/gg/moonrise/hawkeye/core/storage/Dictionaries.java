package gg.moonrise.hawkeye.core.storage;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import gg.moonrise.hawkeye.core.repository.ActorRepository;
import gg.moonrise.hawkeye.core.repository.DictionaryRepository;
import gg.moonrise.hawkeye.core.repository.ServerRepository;
import gg.moonrise.hawkeye.core.sql.Sql;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.hawkeye.entity.server.ServerNode;
import gg.moonrise.hawkeye.entity.server.WorldKey;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.NotNull;

/**
 * One shard's dictionary cache. Dictionary rows never change once written (an actor's name is the
 * only exception, and the cache notices a new name and writes it), so entries never need
 * invalidation; a miss reads or interns through the repositories on the caller's connection.
 * Thread-safe.
 */
public final class Dictionaries {

    private static final int MAX_TYPES = 500_000;
    private static final int MAX_ACTORS = 250_000;

    private final @NotNull DictionaryRepository dictionaryRepository;
    private final @NotNull ActorRepository actorRepository;
    private final @NotNull ServerRepository serverRepository;
    private final Map<Integer, ServerNode> servers = new ConcurrentHashMap<>();
    private final Cache<String, Integer> typeIds = Caffeine.newBuilder().maximumSize(MAX_TYPES).build();
    private final Cache<Integer, String> typeNames = Caffeine.newBuilder().maximumSize(MAX_TYPES).build();
    private final Map<WorldKey, Integer> worldIds = new ConcurrentHashMap<>();
    private final Map<Integer, WorldKey> worldKeys = new ConcurrentHashMap<>();
    private final Cache<String, KnownActor> actorIds = Caffeine.newBuilder().maximumSize(MAX_ACTORS).build();
    private final Cache<Integer, Actor> actors = Caffeine.newBuilder().maximumSize(MAX_ACTORS).build();

    /**
     * A cache over a shard's dictionaries.
     *
     * @param dictionaryRepository the type and world dictionaries
     * @param actorRepository      the actor dictionary
     * @param serverRepository     the server registry
     */
    public Dictionaries(@NotNull DictionaryRepository dictionaryRepository, @NotNull ActorRepository actorRepository,
            @NotNull ServerRepository serverRepository) {
        this.dictionaryRepository = dictionaryRepository;
        this.actorRepository = actorRepository;
        this.serverRepository = serverRepository;
    }

    /**
     * Registered servers by node id, re-reading the registry when a node is unknown (a server that
     * registered after the cache was filled).
     *
     * @param connection a connection
     * @param sql        the shard's SQL context
     * @param nodes      node ids
     * @return node id to server, for the nodes that exist
     * @throws SQLException when reading fails
     */
    public @NotNull Map<Integer, ServerNode> servers(@NotNull Connection connection, @NotNull Sql sql,
            @NotNull Collection<Integer> nodes) throws SQLException {
        if (!servers.keySet().containsAll(nodes)) {
            refreshServers(connection, sql);
        }
        Map<Integer, ServerNode> found = new HashMap<>();
        for (Integer node : nodes) {
            ServerNode server = servers.get(node);
            if (server != null) {
                found.put(node, server);
            }
        }
        return found;
    }

    /**
     * Every registered server, read from the database.
     *
     * @param connection a connection
     * @param sql        the shard's SQL context
     * @return the servers by node id
     * @throws SQLException when reading fails
     */
    public @NotNull List<ServerNode> refreshServers(@NotNull Connection connection, @NotNull Sql sql)
            throws SQLException {
        List<ServerNode> all = serverRepository.all(connection, sql);
        all.forEach(server -> servers.put(server.node(), server));
        return all;
    }

    /**
     * Type ids for the write path, interning missing names.
     *
     * @param connection a connection in auto-commit mode
     * @param sql        the shard's SQL context
     * @param names      namespaced names
     * @return name to id, for every name
     * @throws SQLException when interning fails
     */
    public @NotNull Map<String, Integer> internTypes(@NotNull Connection connection, @NotNull Sql sql,
            @NotNull Collection<String> names) throws SQLException {
        Map<String, Integer> ids = new HashMap<>();
        List<String> missing = new ArrayList<>();
        for (String name : names) {
            Integer id = typeIds.getIfPresent(name);
            if (id == null) {
                missing.add(name);
            } else {
                ids.put(name, id);
            }
        }
        if (!missing.isEmpty()) {
            dictionaryRepository.internTypes(connection, sql, missing).forEach((name, id) -> {
                rememberType(id, name);
                ids.put(name, id);
            });
        }
        return ids;
    }

    /**
     * Type names for the read path.
     *
     * @param connection a connection
     * @param sql        the shard's SQL context
     * @param ids        type ids
     * @return id to name, for the ids that exist
     * @throws SQLException when reading fails
     */
    public @NotNull Map<Integer, String> typeNames(@NotNull Connection connection, @NotNull Sql sql,
            @NotNull Collection<Integer> ids) throws SQLException {
        Map<Integer, String> names = new HashMap<>();
        List<Integer> missing = new ArrayList<>();
        for (Integer id : ids) {
            String name = typeNames.getIfPresent(id);
            if (name == null) {
                missing.add(id);
            } else {
                names.put(id, name);
            }
        }
        if (!missing.isEmpty()) {
            dictionaryRepository.typeNames(connection, sql, missing).forEach((id, name) -> {
                rememberType(id, name);
                names.put(id, name);
            });
        }
        return names;
    }

    /**
     * World ids of a scope for the write path, interning missing worlds.
     *
     * @param connection a connection in auto-commit mode
     * @param sql        the shard's SQL context
     * @param scope      the world scope
     * @param names      world names
     * @return world name to id, for every name
     * @throws SQLException when interning fails
     */
    public @NotNull Map<String, Integer> internWorlds(@NotNull Connection connection, @NotNull Sql sql,
            @NotNull String scope, @NotNull Collection<String> names) throws SQLException {
        Map<String, Integer> ids = new HashMap<>();
        boolean missing = false;
        for (String name : names) {
            Integer id = worldIds.get(new WorldKey(scope, name));
            if (id == null) {
                missing = true;
            } else {
                ids.put(name, id);
            }
        }
        if (missing) {
            dictionaryRepository.internWorlds(connection, sql, scope, names).forEach((name, id) -> {
                rememberWorld(id, new WorldKey(scope, name));
                ids.put(name, id);
            });
        }
        return ids;
    }

    /**
     * Every world of some scopes (or of the whole shard), always read from the database so worlds other
     * servers created are included.
     *
     * @param connection a connection
     * @param sql        the shard's SQL context
     * @param scopes     world scopes, or empty for all
     * @return world id to key
     * @throws SQLException when reading fails
     */
    public @NotNull Map<Integer, WorldKey> worlds(@NotNull Connection connection, @NotNull Sql sql,
            @NotNull Collection<String> scopes) throws SQLException {
        Map<Integer, WorldKey> worlds = dictionaryRepository.worlds(connection, sql, scopes);
        worlds.forEach(this::rememberWorld);
        return worlds;
    }

    /**
     * A world's key from the cache, reading the shard's worlds on a miss.
     *
     * @param connection a connection
     * @param sql        the shard's SQL context
     * @param ids        world ids
     * @return id to key, for the ids that exist
     * @throws SQLException when reading fails
     */
    public @NotNull Map<Integer, WorldKey> worldKeys(@NotNull Connection connection, @NotNull Sql sql,
            @NotNull Collection<Integer> ids) throws SQLException {
        if (!worldKeys.keySet().containsAll(ids)) {
            worlds(connection, sql, List.of());
        }
        Map<Integer, WorldKey> keys = new HashMap<>();
        for (Integer id : ids) {
            WorldKey key = worldKeys.get(id);
            if (key != null) {
                keys.put(id, key);
            }
        }
        return keys;
    }

    /**
     * Actor ids for the write path, interning missing actors and writing changed player names.
     *
     * @param connection a connection in auto-commit mode
     * @param sql        the shard's SQL context
     * @param actorList  the actors
     * @return actor key to id, for every actor
     * @throws SQLException when interning fails
     */
    public @NotNull Map<String, Integer> internActors(@NotNull Connection connection, @NotNull Sql sql,
            @NotNull Collection<Actor> actorList) throws SQLException {
        Map<String, Integer> ids = new HashMap<>();
        List<Actor> missing = new ArrayList<>();
        for (Actor actor : actorList) {
            KnownActor known = actorIds.getIfPresent(actor.key());
            if (known != null && known.name().equals(actor.name())) {
                ids.put(actor.key(), known.id());
            } else {
                missing.add(actor);
            }
        }
        if (!missing.isEmpty()) {
            Map<String, Integer> interned = actorRepository.intern(connection, sql, missing);
            for (Actor actor : missing) {
                Integer id = interned.get(actor.key());
                if (id != null) {
                    rememberActor(id, actor);
                    ids.put(actor.key(), id);
                }
            }
        }
        return ids;
    }

    /**
     * Actors for the read path.
     *
     * @param connection a connection
     * @param sql        the shard's SQL context
     * @param ids        actor ids
     * @return id to actor, for the ids that exist
     * @throws SQLException when reading fails
     */
    public @NotNull Map<Integer, Actor> actors(@NotNull Connection connection, @NotNull Sql sql,
            @NotNull Collection<Integer> ids) throws SQLException {
        Map<Integer, Actor> found = new HashMap<>();
        List<Integer> missing = new ArrayList<>();
        for (Integer id : ids) {
            Actor actor = actors.getIfPresent(id);
            if (actor == null) {
                missing.add(id);
            } else {
                found.put(id, actor);
            }
        }
        if (!missing.isEmpty()) {
            actorRepository.byIds(connection, sql, missing).forEach((id, actor) -> {
                rememberActor(id, actor);
                found.put(id, actor);
            });
        }
        return found;
    }

    private void rememberType(int id, @NotNull String name) {
        typeIds.put(name, id);
        typeNames.put(id, name);
    }

    private void rememberWorld(int id, @NotNull WorldKey key) {
        worldIds.put(key, id);
        worldKeys.put(id, key);
    }

    private void rememberActor(int id, @NotNull Actor actor) {
        actorIds.put(actor.key(), new KnownActor(id, actor.name()));
        actors.put(id, actor);
    }

    private record KnownActor(int id, @NotNull String name) {
    }
}
