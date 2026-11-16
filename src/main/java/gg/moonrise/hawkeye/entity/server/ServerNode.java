package gg.moonrise.hawkeye.entity.server;

import org.jetbrains.annotations.NotNull;

/**
 * A server registered in a shard.
 *
 * @param node     its node id (1 to 1023), the middle bits of every event id it writes
 * @param name     its configured name
 * @param scope    its world scope
 * @param lastSeen its last heartbeat, Unix milliseconds
 */
public record ServerNode(int node, @NotNull String name, @NotNull String scope, long lastSeen) {
}
