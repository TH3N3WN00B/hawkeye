package gg.moonrise.hawkeye.core.bus;

import gg.moonrise.hawkeye.config.provider.HawkeyeConfigProvider;
import gg.moonrise.hawkeye.config.file.NetworkSettings;
import gg.moonrise.hawkeye.core.scheduler.HawkeyeExecutors;
import gg.moonrise.moss.spring.Enableable;
import gg.moonrise.moss.spring.SpringComponent;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

/**
 * Best-effort notifications between servers: "a job is waiting for you", "your job finished". With
 * {@code network.bus: REDIS} they travel over Redis pub/sub; with {@code NONE} publishing does
 * nothing. Either way the database is the source of truth and every receiver also polls it, so a
 * lost message only costs latency (Lettuce can resubscribe silently after a short drop).
 *
 * <p>Handlers run one at a time on the bus's own dispatch thread and must only hand work off.
 */
@Slf4j
@SpringComponent
@RequiredArgsConstructor
public final class SyncBus implements Enableable {

    private static final long RETRY_SECONDS = 30;

    private final @NotNull HawkeyeConfigProvider configProvider;
    private final @NotNull HawkeyeExecutors executors;
    private final Map<String, List<Consumer<String>>> handlers = new ConcurrentHashMap<>();
    private final ExecutorService dispatch = Executors.newSingleThreadExecutor(HawkeyeExecutors.threads("Hawkeye-Bus"));
    private volatile RedisClient client;
    private volatile StatefulRedisConnection<String, String> publisher;
    private volatile StatefulRedisPubSubConnection<String, String> subscriber;
    private volatile boolean closed;

    @Override
    public void onEnable() {
        if (configProvider.get().network().bus() == NetworkSettings.BusType.REDIS) {
            executors.maintenance().execute(this::connect);
        }
    }

    /**
     * Whether messages currently travel over Redis.
     *
     * @return {@code true} while connected
     */
    public boolean connected() {
        StatefulRedisConnection<String, String> connection = publisher;
        return connection != null && connection.isOpen();
    }

    /**
     * Publishes a message; does nothing without a connected bus.
     *
     * @param channel the channel name without the configured prefix
     * @param message the message
     */
    public void publish(@NotNull String channel, @NotNull String message) {
        StatefulRedisConnection<String, String> connection = publisher;
        if (connection != null && connection.isOpen()) {
            connection.async().publish(prefixed(channel), message).exceptionally(error -> {
                log.debug("Could not publish to {}", channel, error);
                return 0L;
            });
        }
    }

    /**
     * Calls a handler for every message on a channel (from now on, and again after a reconnect).
     *
     * @param channel the channel name without the configured prefix
     * @param handler the handler; it runs on the bus thread and must return quickly
     */
    public void subscribe(@NotNull String channel, @NotNull Consumer<String> handler) {
        handlers.computeIfAbsent(prefixed(channel), key -> new CopyOnWriteArrayList<>()).add(handler);
        StatefulRedisPubSubConnection<String, String> connection = subscriber;
        if (connection != null && connection.isOpen()) {
            connection.async().subscribe(prefixed(channel));
        }
    }

    /** Closes the Redis connections. */
    @PreDestroy
    public void close() {
        closed = true;
        dispatch.shutdownNow();
        closeQuietly();
    }

    private void connect() {
        if (closed) {
            return;
        }
        String uri = configProvider.get().network().redisUri();
        try {
            RedisClient redis = RedisClient.create(uri);
            StatefulRedisPubSubConnection<String, String> pubSub = redis.connectPubSub();
            pubSub.addListener(new RedisPubSubAdapter<>() {
                @Override
                public void message(String channel, String message) {
                    deliver(channel, message);
                }
            });
            if (!handlers.isEmpty()) {
                pubSub.sync().subscribe(handlers.keySet().toArray(String[]::new));
            }
            client = redis;
            subscriber = pubSub;
            publisher = redis.connect();
            log.info("Connected to the Redis bus at {}", redact(uri));
        } catch (RuntimeException e) {
            closeQuietly();
            log.warn("Could not connect to Redis at {} (retrying in {} s; jobs still arrive by polling): {}",
                    redact(uri), RETRY_SECONDS, Objects.requireNonNullElse(e.getMessage(), e.getClass().getName()));
            try {
                executors.maintenance().schedule(this::connect, RETRY_SECONDS, TimeUnit.SECONDS);
            } catch (RejectedExecutionException rejected) {
                log.debug("Not retrying Redis: shutting down");
            }
        }
    }

    private void deliver(@NotNull String channel, @NotNull String message) {
        List<Consumer<String>> listeners = handlers.get(channel);
        if (listeners == null) {
            return;
        }
        try {
            dispatch.execute(() -> listeners.forEach(listener -> {
                try {
                    listener.accept(message);
                } catch (RuntimeException e) {
                    log.warn("A bus handler for {} failed", channel, e);
                }
            }));
        } catch (RejectedExecutionException e) {
            log.debug("Dropped a bus message while shutting down");
        }
    }

    private void closeQuietly() {
        StatefulRedisConnection<String, String> pub = publisher;
        StatefulRedisPubSubConnection<String, String> sub = subscriber;
        RedisClient redis = client;
        publisher = null;
        subscriber = null;
        client = null;
        try {
            if (pub != null) {
                pub.close();
            }
            if (sub != null) {
                sub.close();
            }
            if (redis != null) {
                redis.shutdown();
            }
        } catch (RuntimeException e) {
            log.debug("Closing the Redis bus failed", e);
        }
    }

    private @NotNull String prefixed(@NotNull String channel) {
        return configProvider.get().network().channel() + ":" + channel;
    }

    private static @NotNull String redact(@NotNull String uri) {
        int at = uri.lastIndexOf('@');
        int scheme = uri.indexOf("://");
        return at > 0 && scheme > 0 ? uri.substring(0, scheme + 3) + "***" + uri.substring(at) : uri;
    }
}
