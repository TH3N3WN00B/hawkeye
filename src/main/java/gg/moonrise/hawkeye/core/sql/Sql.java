package gg.moonrise.hawkeye.core.sql;

import org.jetbrains.annotations.NotNull;

/**
 * What a repository needs to build statements for a shard.
 *
 * @param dialect the engine
 * @param tables  the table names
 */
public record Sql(@NotNull Dialect dialect, @NotNull Tables tables) {
}
