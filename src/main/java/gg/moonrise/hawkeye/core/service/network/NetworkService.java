package gg.moonrise.hawkeye.core.service.network;

import gg.moonrise.hawkeye.config.HawkeyeConfigProvider;
import gg.moonrise.hawkeye.core.scheduler.HawkeyeExecutors;
import gg.moonrise.hawkeye.core.storage.EventStore;
import gg.moonrise.hawkeye.core.storage.Shard;
import gg.moonrise.hawkeye.core.storage.StorageManager;
import gg.moonrise.hawkeye.entity.server.ServerNode;
import gg.moonrise.moss.spring.SpringComponent;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

/** What {@code /hawkeye network} shows: every server registered in every shard and whether it is alive. */
@SpringComponent
@RequiredArgsConstructor
public final class NetworkService {

    /** A server missing this many heartbeats counts as offline. */
    public static final int MISSED_HEARTBEATS = 3;

    private final @NotNull StorageManager storage;
    private final @NotNull EventStore store;
    private final @NotNull HawkeyeExecutors executors;
    private final @NotNull HawkeyeConfigProvider configProvider;
    private final @NotNull Clock clock;

    /**
     * Reads every shard's server registry.
     *
     * @return the servers and the shards that could not be read
     */
    public @NotNull CompletableFuture<NetworkView> view() {
        try {
            return CompletableFuture.supplyAsync(this::read, executors.query());
        } catch (RejectedExecutionException e) {
            return CompletableFuture.completedFuture(new NetworkView(List.of(), Map.of("*", "busy")));
        }
    }

    private @NotNull NetworkView read() {
        long now = clock.millis();
        long offlineAfter = MISSED_HEARTBEATS * 1000L * configProvider.get().network().heartbeatSeconds();
        List<NetworkServer> servers = new ArrayList<>();
        Map<String, String> failures = new LinkedHashMap<>();
        for (Shard shard : storage.shards()) {
            try {
                for (ServerNode node : store.servers(shard)) {
                    servers.add(new NetworkServer(shard.name(), node.name(), node.node(), now - node.lastSeen(),
                            now - node.lastSeen() <= offlineAfter));
                }
            } catch (SQLException | RuntimeException e) {
                failures.put(shard.name(), Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName()));
            }
        }
        return new NetworkView(servers, failures);
    }

    /**
     * The network as one server sees it.
     *
     * @param servers  every registered server
     * @param failures shard name to the reason it could not be read
     */
    public record NetworkView(@NotNull List<NetworkServer> servers, @NotNull Map<String, String> failures) {

        /**
         * Copies the collections.
         *
         * @param servers  the servers
         * @param failures the failures
         */
        public NetworkView {
            servers = List.copyOf(servers);
            failures = Map.copyOf(failures);
        }
    }

    /**
     * One registered server.
     *
     * @param shard      its shard
     * @param name       its name
     * @param node       its node id
     * @param silentMillis time since its last heartbeat
     * @param online     whether its heartbeat is recent
     */
    public record NetworkServer(@NotNull String shard, @NotNull String name, int node, long silentMillis,
            boolean online) {
    }
}
