package gg.moonrise.hawkeye.core.storage;

import gg.moonrise.hawkeye.core.repository.ActorIndexRepository;
import gg.moonrise.hawkeye.core.repository.EventRepository;
import gg.moonrise.hawkeye.core.repository.PlaceIndexRepository;
import gg.moonrise.hawkeye.core.repository.SegmentIndexRepository;
import gg.moonrise.hawkeye.core.repository.SegmentRepository;
import gg.moonrise.hawkeye.core.repository.place.PlaceChunk;
import org.jetbrains.annotations.NotNull;

/** Builds an {@link Archive} the way the container wires it, for tests that construct storage by hand. */
public final class Archives {

    private Archives() {
    }

    /**
     * An archive with the production segment index.
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
        return create(segments, busySegments, PlaceChunk.EXACT_SEGMENTS);
    }

    /**
     * An archive whose place index calls a chunk busy after fewer segments and keeps fewer exact ids.
     *
     * @param segments      the segment repository (a counting one, say)
     * @param busySegments  the busy threshold
     * @param exactSegments how many of a position's newest segment ids are kept exactly
     * @return the archive
     */
    public static @NotNull Archive create(@NotNull SegmentRepository segments, int busySegments, int exactSegments) {
        SegmentCache cache = new SegmentCache();
        return new Archive(new EventRepository(), segments, cache, index(segments, cache, busySegments, exactSegments));
    }

    /**
     * A segment index over some repositories.
     *
     * @param segments      the segment repository
     * @param cache         the header cache the archive shares
     * @param busySegments  the busy threshold
     * @param exactSegments how many of a position's newest segment ids are kept exactly
     * @return the index
     */
    public static @NotNull SegmentIndex index(@NotNull SegmentRepository segments, @NotNull SegmentCache cache,
            int busySegments, int exactSegments) {
        return new SegmentIndex(new SegmentIndexRepository(),
                new PlaceIndex(new PlaceIndexRepository(), segments, cache, busySegments, exactSegments),
                new ActorIndex(new ActorIndexRepository()), segments, cache);
    }
}
