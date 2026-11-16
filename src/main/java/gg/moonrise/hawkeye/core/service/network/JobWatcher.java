package gg.moonrise.hawkeye.core.service.network;

import gg.moonrise.hawkeye.entity.job.JobResult;
import org.jetbrains.annotations.NotNull;

/** Hears how a dispatched network job ends (the issuing command uses it to tell the sender). */
public interface JobWatcher {

    /**
     * A target server finished the job.
     *
     * @param server the target server
     * @param result what happened
     */
    void finished(@NotNull String server, @NotNull JobResult result);
}
