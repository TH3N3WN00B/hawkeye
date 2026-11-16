package gg.moonrise.hawkeye.entity.job;

import java.util.Optional;
import org.jetbrains.annotations.NotNull;

/** What a network job does. The code is stored in the {@code job} table: never renumber. */
public enum JobKind {

    /** Roll back the query's events on the target server. */
    ROLLBACK(1),
    /** Restore the query's rolled-back events on the target server. */
    RESTORE(2);

    private final int code;

    JobKind(int code) {
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
     * The kind stored under a code.
     *
     * @param code a stored code
     * @return the kind, or empty for an unknown code
     */
    public static @NotNull Optional<JobKind> byCode(int code) {
        for (JobKind kind : values()) {
            if (kind.code == code) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }
}
