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
 * The {@code rollback} section.
 */
@Getter
@Accessors(fluent = true)
@EqualsAndHashCode
@ToString
@Configuration
public final class RollbackSettings {

    @Comment("Radius when a player gives no r: (the console must always give an area).")
    private int defaultRadius = 10;

    @Comment("Largest r: a player may use. 0 means no limit.")
    private int maxRadius = 250;

    @Comment("Blocks changed per chunk task. Lower keeps the tick rate smoother on busy servers.")
    private int blocksPerTick = 2_000;

    @Comment("Chunk tasks in flight at once.")
    private int concurrentChunks = 4;

    @Comment("Refuse rollbacks that would change more events than this (0 means no limit).")
    private int maxEvents = 2_000_000;

    @Comment("Reverse container deposits and withdrawals.")
    private boolean restoreContainers = true;

    @Comment({"Reverse entity changes on rollback: respawn killed and destroyed entities, remove placed and",
            "spawned ones, and put items back into item frames and on armour stands."})
    private boolean restoreEntities = true;

    @Comment("A #preview is cancelled when not applied within this many minutes.")
    private int previewMinutes = 5;

    /**
     * Validation problems.
     *
     * @return one message per problem
     */
    public @NotNull List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (defaultRadius < 1) {
            problems.add("rollback.default-radius must be at least 1");
        }
        if (maxRadius < 0) {
            problems.add("rollback.max-radius must not be negative");
        }
        if (blocksPerTick < 1) {
            problems.add("rollback.blocks-per-tick must be at least 1");
        }
        if (concurrentChunks < 1 || concurrentChunks > 64) {
            problems.add("rollback.concurrent-chunks must be between 1 and 64");
        }
        if (maxEvents < 0) {
            problems.add("rollback.max-events must not be negative");
        }
        if (previewMinutes < 1) {
            problems.add("rollback.preview-minutes must be at least 1");
        }
        return problems;
    }
}
