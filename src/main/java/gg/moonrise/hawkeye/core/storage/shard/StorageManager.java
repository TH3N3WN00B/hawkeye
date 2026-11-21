package gg.moonrise.hawkeye.core.storage.shard;

import gg.moonrise.hawkeye.config.provider.HawkeyeConfigProvider;
import gg.moonrise.hawkeye.config.file.HawkeyeConfig;
import gg.moonrise.hawkeye.config.file.ShardSettings;
import gg.moonrise.hawkeye.core.repository.dictionary.ActorRepository;
import gg.moonrise.hawkeye.core.repository.dictionary.DictionaryRepository;
import gg.moonrise.hawkeye.core.repository.network.ServerRepository;
import gg.moonrise.hawkeye.core.scheduler.HawkeyeExecutors;
import gg.moonrise.hawkeye.core.sql.Database;
import gg.moonrise.hawkeye.core.sql.DatabaseFactory;
import gg.moonrise.hawkeye.core.sql.MigrationResult;
import gg.moonrise.hawkeye.core.sql.Migrations;
import gg.moonrise.hawkeye.entity.server.ServerNode;
import gg.moonrise.moss.spring.Enableable;
import gg.moonrise.moss.spring.SpringComponent;
import jakarta.annotation.PreDestroy;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

/**
 * Owns every shard's pool. At enable it opens the pools without touching the database, then, on the
 * maintenance thread, migrates the home schema and registers this server, retrying with back-off
 * until it works: a database that is down at start delays writing (events wait in the queue) but
 * never blocks or breaks server start. Remote shards are opened read-only and never migrated. Pools
 * close when the Spring context closes, after the writer has drained in its {@code onDisable}.
 */
@Slf4j
@SpringComponent
@RequiredArgsConstructor
public final class StorageManager implements Enableable {

    private static final long MAX_RETRY_SECONDS = 60;

    private final @NotNull JavaPlugin plugin;
    private final @NotNull HawkeyeConfigProvider configProvider;
    private final @NotNull DatabaseFactory databaseFactory;
    private final @NotNull DictionaryRepository dictionaryRepository;
    private final @NotNull ActorRepository actorRepository;
    private final @NotNull ServerRepository serverRepository;
    private final @NotNull HawkeyeExecutors executors;
    private final @NotNull Clock clock;

    private final AtomicReference<StorageState> state = new AtomicReference<>(new StorageState.Connecting());
    private final CompletableFuture<StorageState.Ready> ready = new CompletableFuture<>();
    private final List<Shard> remotes = new ArrayList<>();
    private volatile Shard home;
    private volatile boolean closed;

    @Override
    public void onEnable() {
        HawkeyeConfig config = configProvider.get();
        home = shard(ShardSettings.HOME, databaseFactory.open(ShardSettings.HOME, config.storage(),
                plugin.getDataPath()), true);
        for (ShardSettings remote : config.network().shards()) {
            remotes.add(shard(remote.name(), databaseFactory.open(remote.name(), remote.storage(),
                    plugin.getDataPath()), false));
        }
        schedule(0, 1);
        long heartbeat = config.network().heartbeatSeconds();
        executors.maintenance().scheduleWithFixedDelay(this::heartbeat, heartbeat, heartbeat, TimeUnit.SECONDS);
    }

    /**
     * The home shard's state.
     *
     * @return the current state
     */
    public @NotNull StorageState state() {
        return state.get();
    }

    /**
     * Completes once the home shard is migrated and this server registered.
     *
     * @return the ready state
     */
    public @NotNull CompletableFuture<StorageState.Ready> ready() {
        return ready.copy();
    }

    /**
     * The ready state, if reached.
     *
     * @return the state, or empty while connecting or failed
     */
    public @NotNull Optional<StorageState.Ready> readyNow() {
        return state.get() instanceof StorageState.Ready readyState ? Optional.of(readyState) : Optional.empty();
    }

    /**
     * The home shard (open, but possibly not yet migrated: check {@link #readyNow()} before writing).
     *
     * @return the home shard
     */
    public @NotNull Shard home() {
        return Objects.requireNonNull(home, "storage is opened in onEnable");
    }

    /**
     * Every shard: home first, then the configured remote shards.
     *
     * @return the shards
     */
    public @NotNull List<Shard> shards() {
        List<Shard> all = new ArrayList<>(remotes.size() + 1);
        all.add(home());
        all.addAll(remotes);
        return all;
    }

    /**
     * This server's world scope.
     *
     * @return the configured scope, or the server name
     */
    public @NotNull String worldScope() {
        return configProvider.get().server().effectiveWorldScope();
    }

    /** Closes every pool. */
    @PreDestroy
    public void close() {
        closed = true;
        openShards().forEach(shard -> shard.database().close());
    }

    private @NotNull List<Shard> openShards() {
        List<Shard> all = new ArrayList<>(remotes);
        if (home != null) {
            all.addFirst(home);
        }
        return all;
    }

    private @NotNull Shard shard(@NotNull String name, @NotNull Database database, boolean isHome) {
        return new Shard(name, database, new Dictionaries(dictionaryRepository, actorRepository, serverRepository),
                isHome);
    }

    private void schedule(long delaySeconds, int attempt) {
        if (closed) {
            return;
        }
        try {
            executors.maintenance().schedule(() -> connect(attempt), delaySeconds, TimeUnit.SECONDS);
        } catch (RejectedExecutionException e) {
            log.debug("Not connecting: the plugin is shutting down");
        }
    }

    private void connect(int attempt) {
        if (closed) {
            return;
        }
        Shard shard = home();
        HawkeyeConfig config = configProvider.get();
        try {
            MigrationResult migration = shard.database().read(connection -> Migrations.apply(connection,
                    shard.database().dialect(), shard.database().tables()));
            switch (migration) {
                case MigrationResult.DatabaseAhead ahead -> {
                    state.set(new StorageState.Incompatible(ahead.version()));
                    log.error("The database has schema version {}, newer than this Hawkeye understands; nothing "
                            + "will be written until Hawkeye is updated", ahead.version());
                    return;
                }
                case MigrationResult.Migrated migrated ->
                        log.info("Database schema migrated from version {} to {}", migrated.from(), migrated.to());
                case MigrationResult.UpToDate upToDate ->
                        log.debug("Database schema is up to date (version {})", upToDate.version());
            }
            ServerNode node = shard.database().read(connection -> serverRepository.register(connection,
                    shard.database().sql(), config.server().name(), config.server().effectiveWorldScope(),
                    clock.millis()));
            StorageState.Ready readyState = new StorageState.Ready(shard, node);
            state.set(readyState);
            ready.complete(readyState);
            log.info("Connected to {} as server '{}' (node {})", shard.database().dialect(), node.name(), node.node());
        } catch (SQLException | RuntimeException e) {
            long delay = Math.min(MAX_RETRY_SECONDS, 1L << Math.min(attempt, 6));
            String reason = Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName());
            state.set(new StorageState.Failed(reason));
            log.warn("Could not connect to the database (attempt {}), retrying in {} s: {}", attempt, delay, reason);
            schedule(delay, attempt + 1);
        }
    }

    private void heartbeat() {
        readyNow().ifPresent(readyState -> {
            try {
                readyState.home().database().read(connection -> {
                    serverRepository.heartbeat(connection, readyState.home().database().sql(),
                            readyState.node().name(), clock.millis());
                    return null;
                });
            } catch (SQLException | RuntimeException e) {
                log.debug("Heartbeat failed", e);
            }
        });
    }
}
