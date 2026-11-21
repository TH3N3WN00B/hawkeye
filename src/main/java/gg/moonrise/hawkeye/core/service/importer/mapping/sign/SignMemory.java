package gg.moonrise.hawkeye.core.service.importer.mapping.sign;

import gg.moonrise.hawkeye.entity.payload.SignSides;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;

/**
 * What the mapper remembers about signs between rows, per world and position, in bounded
 * insertion-ordered maps: the block state of the last sign block seen there (CoreProtect's {@code sign}
 * rows carry no material), the "before" text waiting for the next edit, and the text of a broken sign
 * waiting for its {@code block} break (written five seconds earlier).
 */
public final class SignMemory {

    /** The most sign block states kept. */
    public static final int STATES = 65_536;

    /** The most waiting texts kept, of each kind. */
    public static final int TEXTS = 4_096;

    private final Map<Place, String> states = bounded(STATES);
    private final Map<Place, SignSides> before = bounded(TEXTS);
    private final Map<Place, SignSides> broken = bounded(TEXTS);

    /**
     * Remembers the block state of a sign block.
     *
     * @param world    the source world id
     * @param position the packed position
     * @param state    the block state
     */
    public void state(int world, long position, @NotNull String state) {
        Place place = new Place(world, position);
        states.remove(place);
        states.put(place, state);
    }

    /**
     * The last sign block state seen at a position.
     *
     * @param world    the source world id
     * @param position the packed position
     * @return the state, or empty
     */
    public @NotNull Optional<String> state(int world, long position) {
        return Optional.ofNullable(states.get(new Place(world, position)));
    }

    /**
     * Remembers a "before" snapshot for the next edit at a position.
     *
     * @param world    the source world id
     * @param position the packed position
     * @param text     the text before the edit
     */
    public void before(int world, long position, @NotNull SignSides text) {
        before.put(new Place(world, position), text);
    }

    /**
     * Takes the "before" snapshot of a position.
     *
     * @param world    the source world id
     * @param position the packed position
     * @return the text, or empty when none waits
     */
    public @NotNull Optional<SignSides> takeBefore(int world, long position) {
        return Optional.ofNullable(before.remove(new Place(world, position)));
    }

    /**
     * Remembers the text of a broken sign for the block break at its position.
     *
     * @param world    the source world id
     * @param position the packed position
     * @param text     the sign's text
     */
    public void broken(int world, long position, @NotNull SignSides text) {
        broken.put(new Place(world, position), text);
    }

    /**
     * Takes the text of a sign broken at a position.
     *
     * @param world    the source world id
     * @param position the packed position
     * @return the text, or empty when none waits
     */
    public @NotNull Optional<SignSides> takeBroken(int world, long position) {
        return Optional.ofNullable(broken.remove(new Place(world, position)));
    }

    private static <V> @NotNull Map<Place, V> bounded(int capacity) {
        return new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Place, V> eldest) {
                return size() > capacity;
            }
        };
    }

    /**
     * A block in a source world.
     *
     * @param world    the source world id
     * @param position the packed position
     */
    private record Place(int world, long position) {
    }
}
