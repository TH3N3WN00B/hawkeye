package gg.moonrise.hawkeye.entity.job;

import java.util.Optional;
import org.jetbrains.annotations.NotNull;

/**
 * What a finished network job reports, stored as text in the job's {@code result} column:
 * {@code ok|<blocks>|<items>|<entities>|<millis>} or {@code failed|<reason>}.
 */
public sealed interface JobResult {

    /**
     * The job was applied.
     *
     * @param blocks   blocks changed
     * @param items    item stacks changed
     * @param entities entities respawned
     * @param millis   how long it took
     */
    record Applied(long blocks, long items, long entities, long millis) implements JobResult {
    }

    /**
     * The job was not applied.
     *
     * @param reason why
     */
    record Failed(@NotNull String reason) implements JobResult {
    }

    /**
     * The stored text.
     *
     * @return the text for the {@code result} column
     */
    default @NotNull String encode() {
        return switch (this) {
            case Applied applied -> "ok|" + applied.blocks() + "|" + applied.items() + "|" + applied.entities() + "|"
                    + applied.millis();
            case Failed failed -> "failed|" + failed.reason();
        };
    }

    /**
     * Reads a stored result.
     *
     * @param text the {@code result} column
     * @return the result, or empty when the text is not a result
     */
    static @NotNull Optional<JobResult> decode(@NotNull String text) {
        if (text.startsWith("failed|")) {
            return Optional.of(new Failed(text.substring("failed|".length())));
        }
        String[] parts = text.split("\\|");
        if (parts.length != 5 || !parts[0].equals("ok")) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Applied(Long.parseLong(parts[1]), Long.parseLong(parts[2]),
                    Long.parseLong(parts[3]), Long.parseLong(parts[4])));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
