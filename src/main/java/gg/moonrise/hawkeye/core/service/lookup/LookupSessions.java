package gg.moonrise.hawkeye.core.service.lookup;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import gg.moonrise.hawkeye.config.HawkeyeConfigProvider;
import gg.moonrise.moss.spring.SpringComponent;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;

/**
 * Every sender's last lookup, for {@code /hawkeye page}: keyed by player UUID (the console and other
 * non-entity senders by name), and forgotten after {@code lookup.session-minutes} without use. Safe
 * from any thread.
 */
@SpringComponent
public final class LookupSessions {

    private final @NotNull HawkeyeConfigProvider configProvider;
    private final @NotNull Cache<String, LookupSession> sessions;

    /**
     * Builds the cache, timed by the plugin clock.
     *
     * @param configProvider the configuration (for {@code lookup.session-minutes})
     * @param clock          the clock that times expiry
     */
    public LookupSessions(@NotNull HawkeyeConfigProvider configProvider, @NotNull Clock clock) {
        this.configProvider = configProvider;
        this.sessions = Caffeine.newBuilder()
                .expireAfterAccess(lifetime())
                .ticker(() -> TimeUnit.MILLISECONDS.toNanos(clock.millis()))
                // Maintenance runs on the calling thread, never on the common pool.
                .executor(Runnable::run)
                .build();
    }

    /**
     * The key of a sender's session.
     *
     * @param sender the sender
     * @return the UUID of an entity, or {@code #name} for the console and other senders
     */
    public static @NotNull String key(@NotNull CommandSender sender) {
        return sender instanceof Entity entity ? entity.getUniqueId().toString()
                : "#" + sender.getName().toLowerCase(Locale.ROOT);
    }

    /**
     * A sender's session.
     *
     * @param sender the sender
     * @return the session, or empty when there is none or it expired
     */
    public @NotNull Optional<LookupSession> get(@NotNull CommandSender sender) {
        return Optional.ofNullable(sessions.getIfPresent(key(sender)));
    }

    /**
     * Starts a sender's session, replacing any previous one.
     *
     * @param sender  the sender
     * @param session the new session
     */
    public void put(@NotNull CommandSender sender, @NotNull LookupSession session) {
        applyLifetime();
        sessions.put(key(sender), session);
    }

    /**
     * Updates a sender's session only if it is still the one the caller read, so a slow page walk never
     * overwrites a newer lookup.
     *
     * @param sender   the sender
     * @param expected the session the update is based on
     * @param updated  the new state
     * @return whether the session was updated
     */
    public boolean replace(@NotNull CommandSender sender, @NotNull LookupSession expected,
            @NotNull LookupSession updated) {
        return sessions.asMap().replace(key(sender), expected, updated);
    }

    /**
     * Forgets a sender's session.
     *
     * @param sender the sender
     */
    public void clear(@NotNull CommandSender sender) {
        sessions.invalidate(key(sender));
    }

    private void applyLifetime() {
        Duration lifetime = lifetime();
        sessions.policy().expireAfterAccess().ifPresent(expiry -> {
            if (!expiry.getExpiresAfter().equals(lifetime)) {
                expiry.setExpiresAfter(lifetime);
            }
        });
    }

    private @NotNull Duration lifetime() {
        return Duration.ofMinutes(configProvider.get().lookup().sessionMinutes());
    }
}
