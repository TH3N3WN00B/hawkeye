package gg.moonrise.hawkeye.entity.job;

import java.util.Optional;
import org.jetbrains.annotations.NotNull;

/** Where a network job is. The code is stored in the {@code job} table: never renumber. */
public enum JobStatus {

    /** Written by the issuer, not yet claimed. */
    PENDING(0),
    /** Claimed by the target, which is applying it. */
    RUNNING(1),
    /** Applied; the result holds the counts. */
    DONE(2),
    /** Not applied; the result holds the reason. */
    FAILED(3);

    private final int code;

    JobStatus(int code) {
        this.code = code;
    }

    /**
     * The stored code.
     *
     * @return the code
     */
    public int code() {
        return code;
    }

    /**
     * Whether the job has ended.
     *
     * @return {@code true} for done and failed jobs
     */
    public boolean finished() {
        return this == DONE || this == FAILED;
    }

    /**
     * The status stored under a code.
     *
     * @param code a stored code
     * @return the status, or empty for an unknown code
     */
    public static @NotNull Optional<JobStatus> byCode(int code) {
        for (JobStatus status : values()) {
            if (status.code == code) {
                return Optional.of(status);
            }
        }
        return Optional.empty();
    }
}
