package gg.moonrise.hawkeye.core.service.lookup;

import org.jetbrains.annotations.NotNull;

/**
 * A shard a lookup could not read: the result holds what the other shards returned.
 *
 * @param shard  the shard name ({@code home} or a configured remote shard)
 * @param reason why it could not be read
 */
public record LookupWarning(@NotNull String shard, @NotNull String reason) {
}
