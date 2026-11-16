package gg.moonrise.hawkeye.core.storage;

import gg.moonrise.hawkeye.core.repository.EventRepository;
import gg.moonrise.hawkeye.core.repository.PlaceIndexRepository;
import gg.moonrise.hawkeye.core.repository.SegmentRepository;
import org.jetbrains.annotations.NotNull;

/** Builds an {@link Archive} the way the container wires it, for tests that construct storage by hand. */
public final class Archives {

    private Archives() {
    }

    /**
     * An archive with the production place index.
     *
     * @return the archive
     */
    public static @NotNull Archive create() {
        return create(new SegmentRepository(), PlaceIndex.BUSY_SEGMENTS);
    }

    /**
     * An archive whose place index calls a chunk busy after fewer segments.
     *
     * @param segments     the segment repository (a counting one, say)
     * @param busySegments the busy threshold
     * @return the archive
     */
    public static @NotNull Archive create(@NotNull SegmentRepository segments, int busySegments) {
        SegmentCache cache = new SegmentCache();
        return new Archive(new EventRepository(), segments, cache,
                new PlaceIndex(new PlaceIndexRepository(), segments, cache, busySegments));
    }
}
