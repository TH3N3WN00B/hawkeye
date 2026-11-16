package gg.moonrise.hawkeye.core.service.rollback;

import gg.moonrise.hawkeye.core.service.network.JobRunner;
import gg.moonrise.hawkeye.entity.job.JobKind;
import gg.moonrise.hawkeye.entity.job.JobResult;
import gg.moonrise.hawkeye.entity.query.LookupQuery;
import gg.moonrise.moss.spring.SpringComponent;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

/**
 * Applies network jobs addressed to this server with {@link RollbackService#runLocal}: the issuer is
 * the server console under the remote issuer's name (progress goes to the console log, the result
 * back to the issuing server through the job row).
 */
@Slf4j
@SpringComponent
@RequiredArgsConstructor
public final class RollbackJobRunner implements JobRunner {

    private final @NotNull RollbackService rollbackService;
    private final @NotNull JavaPlugin plugin;

    @Override
    public @NotNull CompletableFuture<JobResult> run(@NotNull JobKind kind, @NotNull LookupQuery query,
            @NotNull String issuer) {
        RollbackMode mode = kind == JobKind.ROLLBACK ? RollbackMode.ROLLBACK : RollbackMode.RESTORE;
        RollbackIssuer remote = new RollbackIssuer("job:" + issuer, issuer, plugin.getServer().getConsoleSender(),
                null);
        return rollbackService.runLocal(new RollbackRequest(mode, query, remote, false, RollbackListener.NONE))
                .thenApply(RollbackJobRunner::result)
                .exceptionally(error -> new JobResult.Failed(error.getClass().getSimpleName() + ": "
                        + error.getMessage()));
    }

    private static @NotNull JobResult result(@NotNull RollbackOutcome outcome) {
        return switch (outcome) {
            case RollbackOutcome.Applied applied -> new JobResult.Applied(applied.blocks(), applied.items(),
                    applied.entities(), applied.millis());
            case RollbackOutcome.NothingToDo nothing -> new JobResult.Applied(0, 0, 0, 0);
            case RollbackOutcome.TooLarge large -> new JobResult.Failed("too large: " + large.events() + " events, limit "
                    + large.max());
            case RollbackOutcome.AlreadyRunning running -> new JobResult.Failed("another job from this issuer is running");
            case RollbackOutcome.NotReady notReady -> new JobResult.Failed("the database is not ready");
            case RollbackOutcome.Failed failed -> new JobResult.Failed(failed.reason());
            case RollbackOutcome.Previewed previewed -> new JobResult.Failed("unexpected preview");
            case RollbackOutcome.PreviewCancelled cancelled -> new JobResult.Failed("unexpected preview");
            case RollbackOutcome.NothingToUndo nothingToUndo -> new JobResult.Failed("nothing to undo");
            case RollbackOutcome.NoPreview noPreview -> new JobResult.Failed("no preview");
            case RollbackOutcome.NetworkUnsupported network -> new JobResult.Failed("not local");
            case RollbackOutcome.PreviewNeedsPlayer needsPlayer -> new JobResult.Failed("preview needs a player");
        };
    }
}
