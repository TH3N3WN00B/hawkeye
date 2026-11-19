package gg.moonrise.hawkeye.core.storage.segment;

import java.util.Arrays;
import org.jetbrains.annotations.NotNull;

/**
 * What the header records about one unit.
 *
 * @param rows          how many rows it holds
 * @param placed        whether it holds placed rows (else unplaced ones)
 * @param world         placed units: the header world index of the first row (0 otherwise)
 * @param position      placed units: the packed position of the first row (0 otherwise)
 * @param minSlot       the smallest slot of its rows, minus the header's base slot
 * @param maxSlot       the largest slot of its rows, minus the header's base slot
 * @param scalarLength  the byte length of its scalar frame
 * @param payloadLength the byte length of its payload frame (0 when no row has data)
 * @param actors        ascending indexes into the header's actor list of the actors of its rows
 * @param actionMask    bit {@code min(code, 63)} set for every action code of its rows
 */
public record SegmentUnit(int rows, boolean placed, int world, long position, long minSlot, long maxSlot,
        int scalarLength, int payloadLength, int @NotNull [] actors, long actionMask) {

    /**
     * The length of the unit's frames together.
     *
     * @return scalar plus payload length
     */
    public int length() {
        return scalarLength + payloadLength;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SegmentUnit unit && rows == unit.rows && placed == unit.placed && world == unit.world
                && position == unit.position && minSlot == unit.minSlot && maxSlot == unit.maxSlot
                && scalarLength == unit.scalarLength && payloadLength == unit.payloadLength
                && Arrays.equals(actors, unit.actors) && actionMask == unit.actionMask;
    }

    @Override
    public int hashCode() {
        return (rows * 31 + Long.hashCode(position)) * 31 + Arrays.hashCode(actors);
    }

    @Override
    public @NotNull String toString() {
        return "SegmentUnit[rows=" + rows + ", placed=" + placed + ", world=" + world + ", position=" + position
                + ", slots=" + minSlot + ".." + maxSlot + ", frames=" + scalarLength + "+" + payloadLength
                + ", actors=" + Arrays.toString(actors) + ", actions=" + Long.toHexString(actionMask) + "]";
    }
}
