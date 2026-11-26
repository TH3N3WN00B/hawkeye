package gg.moonrise.hawkeye.core.diagnostic;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Hawkeye's error and crash files: the {@link ErrorLog} and its hook in the server's logging. The plugin opens it
 * first thing in {@code onLoad}, before the Spring context exists (so a context that fails to start is a crash
 * file too), and closes it last in {@code onDisable}. Not a Spring bean for that reason.
 */
@Slf4j
public final class Diagnostics implements AutoCloseable {

    private static final Duration CLOSE_WAIT = Duration.ofSeconds(5);

    private final @NotNull ErrorLog errorLog;
    private final @Nullable ConsoleCapture capture;

    private Diagnostics(@NotNull ErrorLog errorLog, @Nullable ConsoleCapture capture) {
        this.errorLog = errorLog;
        this.capture = capture;
    }

    /**
     * Starts the error log in a plugin's data folder and hooks it into the server's logging.
     *
     * @param plugin Hawkeye
     * @return the open diagnostics
     */
    public static @NotNull Diagnostics open(@NotNull Plugin plugin) {
        ServerDetails details = new ServerDetails(plugin);
        ErrorLog errorLog = new ErrorLog(plugin.getDataPath(), Clock.systemDefaultZone(), details::snapshot);
        errorLog.start();
        ConsoleCapture capture = null;
        try {
            capture = Log4jCapture.install(errorLog, plugin.getName(), Path.of("")).orElse(null);
        } catch (LinkageError e) {
            // A server without log4j-core: nothing to hook into.
        }
        if (capture == null) {
            log.warn("The server does not log through log4j-core; Hawkeye's errors are not written to files");
            errorLog.close(CLOSE_WAIT);
        }
        return new Diagnostics(errorLog, capture);
    }

    /**
     * The error log.
     *
     * @return the error log
     */
    public @NotNull ErrorLog errorLog() {
        return errorLog;
    }

    /** Marks the calling thread as running Hawkeye's load or enable (see {@link ErrorLog#startupBegins()}). */
    public void startupBegins() {
        errorLog.startupBegins();
    }

    /** Ends {@link #startupBegins()}. */
    public void startupEnds() {
        errorLog.startupEnds();
    }

    /** Removes the logging hook, then writes what is left and stops the error log. */
    @Override
    public void close() {
        if (capture != null) {
            capture.close();
        }
        errorLog.close(CLOSE_WAIT);
    }
}
