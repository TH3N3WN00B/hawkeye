package gg.moonrise.hawkeye.entity.server;

import org.jetbrains.annotations.NotNull;

/**
 * A world's identity in a shard: its scope (by default the server name) and its name.
 *
 * @param scope the world scope
 * @param name  the world name
 */
public record WorldKey(@NotNull String scope, @NotNull String name) {
}
