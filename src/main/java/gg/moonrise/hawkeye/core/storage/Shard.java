package gg.moonrise.hawkeye.core.storage;

import gg.moonrise.hawkeye.core.sql.Database;
import org.jetbrains.annotations.NotNull;

/**
 * One database of the network: the home shard this server writes to, or a remote shard it reads and
 * sends network jobs through.
 *
 * @param name         {@code home} or the configured shard name
 * @param database     its pool
 * @param dictionaries its dictionary cache
 * @param home         whether this server writes here
 */
public record Shard(@NotNull String name, @NotNull Database database, @NotNull Dictionaries dictionaries,
        boolean home) {
}
