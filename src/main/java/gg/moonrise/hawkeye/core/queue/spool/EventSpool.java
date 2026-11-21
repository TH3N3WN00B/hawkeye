package gg.moonrise.hawkeye.core.queue.spool;

import gg.moonrise.hawkeye.entity.event.PendingEvent;
import gg.moonrise.hawkeye.util.codec.SpoolCodec;
import gg.moonrise.moss.spring.SpringComponent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.List;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

/**
 * Files of events that could not be written before shutdown ({@code plugins/Hawkeye/spool/}). The
 * writer replays and deletes them once the database is reachable again. A file is written to a
 * temporary name and moved into place, so a crash mid-write never leaves a half file to replay.
 */
@SpringComponent
@RequiredArgsConstructor
public final class EventSpool {

    /** The spool folder inside the plugin folder. */
    public static final String FOLDER = "spool";

    private static final String SUFFIX = ".spool";

    private final @NotNull JavaPlugin plugin;
    private final @NotNull Clock clock;

    /**
     * Writes events to a new spool file.
     *
     * @param events the events
     * @return the file
     * @throws IOException when the file cannot be written
     */
    public @NotNull Path write(@NotNull List<PendingEvent> events) throws IOException {
        Path folder = folder();
        Files.createDirectories(folder);
        Path target = folder.resolve("events-" + clock.millis() + "-" + System.nanoTime() + SUFFIX);
        Path temporary = folder.resolve(target.getFileName() + ".tmp");
        Files.write(temporary, SpoolCodec.encode(events));
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        return target;
    }

    /**
     * The spool files waiting to be replayed, oldest first.
     *
     * @return the files
     * @throws IOException when the folder cannot be listed
     */
    public @NotNull List<Path> files() throws IOException {
        Path folder = folder();
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(file -> file.getFileName().toString().endsWith(SUFFIX)).sorted().toList();
        }
    }

    /**
     * Reads a spool file.
     *
     * @param file the file
     * @return its events
     * @throws IOException when the file cannot be read
     */
    public @NotNull List<PendingEvent> read(@NotNull Path file) throws IOException {
        return SpoolCodec.decode(Files.readAllBytes(file));
    }

    /**
     * Deletes a replayed spool file.
     *
     * @param file the file
     * @throws IOException when it cannot be deleted
     */
    public void delete(@NotNull Path file) throws IOException {
        Files.deleteIfExists(file);
    }

    /**
     * Moves an unreadable spool file aside so it is not retried forever.
     *
     * @param file the file
     * @throws IOException when it cannot be moved
     */
    public void quarantine(@NotNull Path file) throws IOException {
        Files.move(file, file.resolveSibling(file.getFileName() + ".broken"), StandardCopyOption.REPLACE_EXISTING);
    }

    private @NotNull Path folder() {
        return plugin.getDataPath().resolve(FOLDER);
    }
}
