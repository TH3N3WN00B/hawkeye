package gg.moonrise.hawkeye.core.service.query;

import gg.moonrise.hawkeye.core.storage.EventStore;
import gg.moonrise.hawkeye.core.storage.Shard;
import gg.moonrise.hawkeye.core.storage.ShardScope;
import gg.moonrise.hawkeye.core.storage.StorageManager;
import gg.moonrise.hawkeye.entity.query.ServerScope;
import gg.moonrise.hawkeye.entity.server.ServerNode;
import gg.moonrise.moss.spring.SpringComponent;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

/**
 * Which shards, and which of their worlds, a server scope reads. This server reads its own world
 * scope in the home shard; named servers are looked up in every shard's registry (cached briefly, so
 * a server that registers is found within {@link #CACHE_MILLIS}); the network reads every world of
 * every shard. Blocking: call on a query thread.
 */
@SpringComponent
@RequiredArgsConstructor
public final class QueryTargets {

    /** How long a shard's server list is reused. */
    public static final long CACHE_MILLIS = 30_000;

    private final @NotNull StorageManager storage;
    private final @NotNull EventStore store;
    private final @NotNull Clock clock;
    private final Map<String, CachedServers> servers = new ConcurrentHashMap<>();

    /**
     * The shards to read and their world filters.
     *
     * @param scope the server scope
     * @return one target per shard that can hold matching events, home first
     * @throws SQLException when a shard's registry cannot be read
     */
    public @NotNull List<Target> targets(@NotNull ServerScope scope) throws SQLException {
        return switch (scope) {
            case ServerScope.Local local -> List.of(new Target(storage.home(),
                    new ShardScope.Scopes(Set.of(storage.worldScope()))));
            case ServerScope.Network network -> storage.shards().stream()
                    .map(shard -> new Target(shard, new ShardScope.All())).toList();
            case ServerScope.Named named -> named(named.names());
        };
    }

    /**
     * Every registered server of a shard (cached).
     *
     * @param shard the shard
     * @return the servers
     * @throws SQLException when the registry cannot be read
     */
    public @NotNull List<ServerNode> servers(@NotNull Shard shard) throws SQLException {
        long now = clock.millis();
        CachedServers cached = servers.get(shard.name());
        if (cached != null && now - cached.readAt() < CACHE_MILLIS) {
            return cached.servers();
        }
        List<ServerNode> fresh = store.servers(shard);
        servers.put(shard.name(), new CachedServers(fresh, now));
        return fresh;
    }

    private @NotNull List<Target> named(@NotNull Set<String> names) throws SQLException {
        List<Target> targets = new ArrayList<>();
        for (Shard shard : storage.shards()) {
            Set<String> scopes = servers(shard).stream()
                    .filter(server -> names.contains(server.name()))
                    .map(ServerNode::scope)
                    .collect(Collectors.toSet());
            if (!scopes.isEmpty()) {
                targets.add(new Target(shard, new ShardScope.Scopes(scopes)));
            }
        }
        return targets;
    }

    /**
     * One shard to read.
     *
     * @param shard the shard
     * @param scope which of its worlds
     */
    public record Target(@NotNull Shard shard, @NotNull ShardScope scope) {
    }

    private record CachedServers(@NotNull List<ServerNode> servers, long readAt) {
    }
}
