package gg.moonrise.hawkeye.core.service.importer.pipeline;

import java.nio.file.Path;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * What one import run is asked to do, fixed when it starts.
 *
 * @param argument           the operator's source argument, or {@code null} for CoreProtect's own folder
 * @param defaultFolder      CoreProtect's data folder ({@code plugins/CoreProtect})
 * @param baseFolder         what a relative argument is resolved against (the server folder)
 * @param serverName         this server's name (the import node is named after it)
 * @param scope              the world scope imported events are stored under
 * @param batchEvents        events per segment
 * @param checkpointRows     rows after which a checkpoint is written even when no segment filled
 * @param pageRows           rows read from one table at a time
 * @param progressMillis     how often the listener hears about progress
 * @param coreProtectEnabled whether a plugin named CoreProtect is enabled on this server
 * @param startedMillis      when the run began; rows stamped later are left for the next run
 * @param settleSeconds      at least how long before {@code startedMillis} the run's rows end (the source's
 *                           own settle time applies when it is longer; 0 except in tests)
 */
public record RunSettings(
        @Nullable String argument,
        @NotNull Path defaultFolder,
        @NotNull Path baseFolder,
        @NotNull String serverName,
        @NotNull String scope,
        int batchEvents,
        long checkpointRows,
        int pageRows,
        long progressMillis,
        boolean coreProtectEnabled,
        long startedMillis,
        long settleSeconds) {
}
