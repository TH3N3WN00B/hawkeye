package gg.moonrise.hawkeye.core.service.capture;

import gg.moonrise.hawkeye.config.file.LoggingSettings;
import org.jetbrains.annotations.NotNull;

/** The recorded form of a command line: as typed, or only {@code /label} for a redacted command. */
public final class CommandText {

    private CommandText() {
    }

    /**
     * What to record for a command.
     *
     * @param line    the command line as typed, with or without the leading slash
     * @param logging the logging settings that list the redacted commands
     * @return the line with a leading slash, cut to {@code /label} when the command is redacted
     */
    public static @NotNull String recorded(@NotNull String line, @NotNull LoggingSettings logging) {
        String body = line.startsWith("/") ? line.substring(1) : line;
        int space = body.indexOf(' ');
        String label = space < 0 ? body : body.substring(0, space);
        return "/" + (logging.redacts(label) ? label : body);
    }
}
