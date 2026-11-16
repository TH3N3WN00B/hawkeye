package gg.moonrise.hawkeye.config.file;

import de.exlll.configlib.Comment;
import de.exlll.configlib.Configuration;
import java.util.ArrayList;
import java.util.List;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;
import org.jetbrains.annotations.NotNull;

/**
 * The {@code lookup} section.
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
@ToString
@Configuration
public final class LookupSettings {

    private int pageSize = 8;

    @Comment("Radius of /hawkeye near.")
    private int nearRadius = 5;

    @Comment("Largest r: a player may use (the console is not limited). 0 means no limit.")
    private int maxRadius = 250;

    @Comment({"Every lookup first writes the events still queued, so it sees what just happened. This is the",
            "longest it waits for that before reading anyway."})
    private long flushWaitMillis = 2_000;

    @Comment("How long /hawkeye page keeps working after a lookup.")
    private int sessionMinutes = 15;

    @Comment("A lookup that takes longer than this is cancelled and reported.")
    private int timeoutSeconds = 30;

    /**
     * Validation problems.
     *
     * @return one message per problem
     */
    public @NotNull List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (pageSize < 1 || pageSize > 100) {
            problems.add("lookup.page-size must be between 1 and 100");
        }
        if (nearRadius < 0) {
            problems.add("lookup.near-radius must not be negative");
        }
        if (maxRadius < 0) {
            problems.add("lookup.max-radius must not be negative");
        }
        if (flushWaitMillis < 0) {
            problems.add("lookup.flush-wait-millis must not be negative");
        }
        if (sessionMinutes < 1) {
            problems.add("lookup.session-minutes must be at least 1");
        }
        if (timeoutSeconds < 1) {
            problems.add("lookup.timeout-seconds must be at least 1");
        }
        return problems;
    }
}
