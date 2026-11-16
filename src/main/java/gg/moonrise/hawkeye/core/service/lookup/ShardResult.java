package gg.moonrise.hawkeye.core.service.lookup;

import org.jetbrains.annotations.NotNull;

/**
 * What one shard returned for a lookup.
 *
 * @param <T> the value type (a page of events or a count)
 */
public sealed interface ShardResult<T> {

    /**
     * The shard's name.
     *
     * @return {@code home} or a configured remote shard
     */
    @NotNull String shard();

    /**
     * The shard was read.
     *
     * @param shard the shard name
     * @param value what it returned
     * @param <T>   the value type
     */
    record Read<T>(@NotNull String shard, @NotNull T value) implements ShardResult<T> {
    }

    /**
     * The shard could not be read.
     *
     * @param shard  the shard name
     * @param reason why
     * @param <T>    the value type
     */
    record Unreadable<T>(@NotNull String shard, @NotNull String reason) implements ShardResult<T> {
    }
}
