package gg.moonrise.hawkeye.core.service.network;

import gg.moonrise.hawkeye.entity.job.JobKind;
import gg.moonrise.hawkeye.entity.job.JobResult;
import gg.moonrise.hawkeye.entity.query.LookupQuery;
import java.util.concurrent.CompletableFuture;
import org.jetbrains.annotations.NotNull;

/** Applies a network job on this server (implemented over the rollback service). */
public interface JobRunner {

    /**
     * Applies a job's query to this server's worlds.
     *
     * @param kind   rollback or restore
     * @param query  the query, already local to this server
     * @param issuer who asked, for logs and progress messages
     * @return what happened
     */
    @NotNull CompletableFuture<JobResult> run(@NotNull JobKind kind, @NotNull LookupQuery query,
            @NotNull String issuer);
}
