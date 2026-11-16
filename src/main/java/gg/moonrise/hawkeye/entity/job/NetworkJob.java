package gg.moonrise.hawkeye.entity.job;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A rollback or restore that one server asked another to apply to its own worlds, stored in the
 * target's shard ({@code job} table). The database row is the source of truth; the bus only makes the
 * target notice it sooner.
 *
 * @param id           the row id within its shard
 * @param target       the name of the server that applies it
 * @param kind         rollback or restore
 * @param status       where it is
 * @param issuerServer the name of the server that wrote it
 * @param issuer       who asked (player name or {@code #console})
 * @param query        the query, encoded by {@code util.codec.QueryCodec}
 * @param result       the counts when done, the reason when failed, or {@code null} before
 * @param created      when it was written, Unix milliseconds
 * @param updated      when its status last changed
 */
public record NetworkJob(
        long id,
        @NotNull String target,
        @NotNull JobKind kind,
        @NotNull JobStatus status,
        @NotNull String issuerServer,
        @NotNull String issuer,
        @NotNull String query,
        @Nullable String result,
        long created,
        long updated) {
}
