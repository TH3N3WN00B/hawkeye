package gg.moonrise.hawkeye.entity.actor;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Who or what caused an event: a player (keyed by UUID, with the latest known name) or a source
 * (keyed by {@code #name}, e.g. {@code #tnt}, {@code #creeper}, {@code #water}).
 *
 * @param kind player or source
 * @param key  the unique dictionary key: the UUID string for a player, {@code #name} for a source
 * @param name the display name: the player name, or {@code #name} for a source
 */
public record Actor(@NotNull ActorKind kind, @NotNull String key, @NotNull String name) {

    /** The longest stored key or name. */
    public static final int MAX_LENGTH = 64;

    /** The marker that starts every source name. */
    public static final char SOURCE_PREFIX = '#';

    /**
     * Validates the fields.
     *
     * @param kind player or source
     * @param key  the dictionary key
     * @param name the display name
     */
    public Actor {
        if (key.isEmpty() || key.length() > MAX_LENGTH || name.isEmpty() || name.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("actor key and name must have 1 to " + MAX_LENGTH + " characters");
        }
    }

    /**
     * A player.
     *
     * @param uuid the player's UUID
     * @param name the player's current name
     * @return the actor
     */
    public static @NotNull Actor player(@NotNull UUID uuid, @NotNull String name) {
        return new Actor(ActorKind.PLAYER, uuid.toString(), name);
    }

    /**
     * A non-player source. The name is lower-cased and gets a leading {@code #} if it has none.
     *
     * @param name e.g. {@code tnt} or {@code #tnt}
     * @return the actor
     */
    public static @NotNull Actor source(@NotNull String name) {
        String normalized = name.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        if (normalized.isEmpty() || normalized.equals(String.valueOf(SOURCE_PREFIX))) {
            throw new IllegalArgumentException("a source needs a name");
        }
        String key = normalized.charAt(0) == SOURCE_PREFIX ? normalized : SOURCE_PREFIX + normalized;
        return new Actor(ActorKind.SOURCE, key, key);
    }

    /**
     * The player's UUID.
     *
     * @return the UUID, or empty for a source
     */
    public @NotNull Optional<UUID> uuid() {
        return kind == ActorKind.PLAYER ? Optional.of(UUID.fromString(key)) : Optional.empty();
    }
}
