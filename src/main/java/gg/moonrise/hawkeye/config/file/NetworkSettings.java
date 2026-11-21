package gg.moonrise.hawkeye.config.file;

import de.exlll.configlib.Comment;
import de.exlll.configlib.Configuration;
import gg.moonrise.hawkeye.config.env.EnvReferences;
import gg.moonrise.hawkeye.config.env.EnvValue;
import gg.moonrise.hawkeye.config.env.Environment;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;
import org.jetbrains.annotations.NotNull;

/**
 * The {@code network} section. {@code redis-uri} may hold {@code ${NAME}} references to environment
 * variables ({@link EnvReferences}); it is resolved only when the bus connects ({@link #redisUri}) and is
 * never shown by {@link #toString()}.
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
@ToString
@Configuration
public final class NetworkSettings {

    @Comment({"NONE: servers notice network jobs by polling the database. REDIS: jobs start at once over",
            "Redis pub/sub (the database stays the source of truth and polling still runs as a fallback)."})
    private BusType bus = BusType.NONE;

    @Comment({"The Redis server. Put a password in an environment variable rather than here:",
            "redis://:${HAWKEYE_REDIS_PASSWORD}@localhost:6379 (${NAME} is replaced by the variable NAME)."})
    @Getter(AccessLevel.NONE)
    @ToString.Exclude
    private String redisUri = "redis://localhost:6379";

    @Comment("Redis channel prefix; every server of one network uses the same.")
    private String channel = "hawkeye";

    @Comment("Seconds between database checks for network jobs addressed to this server.")
    private int pollIntervalSeconds = 5;

    @Comment("Seconds between heartbeats; /hawkeye network shows servers not seen for 3 heartbeats as offline.")
    private int heartbeatSeconds = 30;

    @Comment({"Other shards (databases written by other servers). Lookups with s:<server> or s:#network read",
            "them and network jobs for their servers are written to them. The home storage is always included."})
    private List<ShardSettings> shards = List.of();

    /**
     * The Redis URI with every {@code ${NAME}} reference resolved. Validation ({@link #problems}) makes sure
     * every referenced variable is set when the bus is {@link BusType#REDIS}.
     *
     * @param environment where variables are looked up
     * @return the URI; it may carry a password, so never log it
     * @throws IllegalStateException when a referenced variable is not set; the message names the variable
     */
    public @NotNull String redisUri(@NotNull Environment environment) {
        return EnvReferences.require(Objects.requireNonNullElse(redisUri, ""), environment, "network.redis-uri")
                .value();
    }

    /**
     * Validation problems. A problem names an unset variable, never the URI or a password.
     *
     * @param environment where variables are looked up
     * @return one message per problem
     */
    public @NotNull List<String> problems(@NotNull Environment environment) {
        List<String> problems = new ArrayList<>();
        if (bus == BusType.REDIS) {
            switch (EnvReferences.resolve(Objects.requireNonNullElse(redisUri, ""), environment)) {
                case EnvValue.Resolved uri when !uri.value().startsWith("redis") ->
                        problems.add("network.redis-uri must be a redis:// or rediss:// URI");
                case EnvValue.Resolved uri -> {
                    // A valid URI.
                }
                case EnvValue.Unset unset -> unset.variables().forEach(variable ->
                        problems.add(EnvReferences.unsetMessage("network.redis-uri", variable)));
            }
        }
        if (!ServerSettings.NAME.matcher(channel).matches()) {
            problems.add("network.channel must be 1-32 of a-z, 0-9, _ and -");
        }
        if (pollIntervalSeconds < 1) {
            problems.add("network.poll-interval-seconds must be at least 1");
        }
        if (heartbeatSeconds < 5) {
            problems.add("network.heartbeat-seconds must be at least 5");
        }
        Set<String> names = new HashSet<>();
        for (int i = 0; i < shards.size(); i++) {
            ShardSettings shard = shards.get(i);
            String path = "network.shards[" + i + "]";
            if (!ServerSettings.NAME.matcher(shard.name()).matches()) {
                problems.add(path + ".name must be 1-32 of a-z, 0-9, _ and -");
            } else if (!names.add(shard.name()) || shard.name().equals(ShardSettings.HOME)) {
                problems.add(path + ".name '" + shard.name() + "' is used twice (or is the reserved 'home')");
            }
            problems.addAll(shard.storage().problems(path + ".storage", environment));
        }
        return problems;
    }

    /** How servers notify each other. */
    public enum BusType {
        /** Database polling only. */
        NONE,
        /** Redis pub/sub, with database polling as the fallback. */
        REDIS
    }
}
