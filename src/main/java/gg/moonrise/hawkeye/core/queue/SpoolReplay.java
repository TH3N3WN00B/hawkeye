package gg.moonrise.hawkeye.core.queue;

import gg.moonrise.hawkeye.entity.event.PendingEvent;
import gg.moonrise.hawkeye.util.EventIds;
import gg.moonrise.moss.spring.SpringComponent;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

/**
 * Writes the spool files a previous run left behind, oldest first, and deletes each once it is stored.
 * Spooled events already carry their final stamps, so they are written as they are and never
 * renumbered. It remembers the newest slot it has read, so the writer can start numbering after it.
 * Used by the writer thread only.
 */
@Slf4j
@SpringComponent
@RequiredArgsConstructor
public final class SpoolReplay {

    private final @NotNull EventSpool spool;
    private long newestSlot = -1;

    /**
     * Replays every spool file.
     *
     * @param write     writes one batch, {@code false} when it failed
     * @param batchSize the most events per batch
     * @return {@code true} when nothing is left to replay; {@code false} when a write failed (the file
     *         stays and is replayed again from the start, which inserts nothing twice)
     */
    public boolean replay(@NotNull Predicate<List<PendingEvent>> write, int batchSize) {
        List<Path> files;
        try {
            files = spool.files();
        } catch (IOException e) {
            log.warn("Could not list the spool folder", e);
            return true;
        }
        for (Path file : files) {
            List<PendingEvent> events = read(file);
            for (int from = 0; from < events.size(); from += batchSize) {
                if (!write.test(events.subList(from, Math.min(events.size(), from + batchSize)))) {
                    return false;
                }
            }
            try {
                spool.delete(file);
                if (!events.isEmpty()) {
                    log.info("Wrote {} spooled event(s) from {}", events.size(), file.getFileName());
                }
            } catch (IOException e) {
                log.warn("Could not delete replayed spool file {}", file.getFileName(), e);
            }
        }
        return true;
    }

    /**
     * The newest slot among the spooled events read so far.
     *
     * @return the slot, or -1 when none was read
     */
    public long newestSlot() {
        return newestSlot;
    }

    /**
     * Reads every spool file still waiting, without writing it, and returns the newest slot of all
     * spooled events seen. The writer uses it at a shutdown that never reached the database, so the
     * events it spools never take a rank an older spool file already holds.
     *
     * @return the slot, or -1 when there is none
     */
    public long scanNewestSlot() {
        try {
            for (Path file : spool.files()) {
                read(file);
            }
        } catch (IOException e) {
            log.warn("Could not list the spool folder", e);
        }
        return newestSlot;
    }

    /** Reads a file and notes its newest slot; a file that cannot be read is moved aside and reads as empty. */
    private @NotNull List<PendingEvent> read(@NotNull Path file) {
        List<PendingEvent> events;
        try {
            events = spool.read(file);
        } catch (IOException | RuntimeException e) {
            log.error("Spool file {} cannot be read; moving it aside", file.getFileName(), e);
            quarantine(file);
            return List.of();
        }
        for (PendingEvent event : events) {
            newestSlot = Math.max(newestSlot, EventIds.stampSlot(event.stamp()));
        }
        return events;
    }

    private void quarantine(@NotNull Path file) {
        try {
            spool.quarantine(file);
        } catch (IOException e) {
            log.error("Could not move spool file {} aside", file.getFileName(), e);
        }
    }
}
