package gg.moonrise.hawkeye.core.storage.index.place;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import org.jetbrains.annotations.NotNull;

/**
 * The places one segment's rows are at, placed and unplaced rows alike: per chunk, the distinct
 * in-chunk keys ({@link PlaceKeys#local}), ascending. What the place index records for a segment.
 */
public final class SegmentPlaces {

    private final long segment;
    private final TreeMap<ChunkRef, int[]> chunks;

    private SegmentPlaces(long segment, @NotNull TreeMap<ChunkRef, int[]> chunks) {
        this.segment = segment;
        this.chunks = chunks;
    }

    /**
     * A builder for one segment.
     *
     * @param segment the segment id
     * @return the builder
     */
    public static @NotNull Builder builder(long segment) {
        return new Builder(segment);
    }

    /**
     * The segment id.
     *
     * @return the id
     */
    public long segment() {
        return segment;
    }

    /**
     * Per chunk: its in-chunk keys, ascending and distinct.
     *
     * @return a read-only view (do not modify the arrays)
     */
    public @NotNull NavigableMap<ChunkRef, int[]> chunks() {
        return Collections.unmodifiableNavigableMap(chunks);
    }

    /** Collects places in any order. Not thread-safe. */
    public static final class Builder {

        private final long segment;
        private final Map<ChunkRef, Locals> chunks = new HashMap<>();
        private ChunkRef last;
        private Locals lastLocals;

        private Builder(long segment) {
            this.segment = segment;
        }

        /**
         * Adds a row's place.
         *
         * @param world    the world id
         * @param position the packed position
         * @return this builder
         */
        public @NotNull Builder add(int world, long position) {
            long chunk = PlaceKeys.chunk(position);
            if (last == null || last.world() != world || last.chunk() != chunk) {
                last = new ChunkRef(world, chunk);
                lastLocals = chunks.computeIfAbsent(last, ignored -> new Locals());
            }
            lastLocals.add(PlaceKeys.local(position));
            return this;
        }

        /**
         * The places collected.
         *
         * @return the places
         */
        public @NotNull SegmentPlaces build() {
            TreeMap<ChunkRef, int[]> out = new TreeMap<>();
            chunks.forEach((chunk, locals) -> out.put(chunk, locals.sortedDistinct()));
            return new SegmentPlaces(segment, out);
        }
    }

    /** A growable list of in-chunk keys. */
    private static final class Locals {

        private int[] values = new int[8];
        private int size;

        private void add(int value) {
            if (size > 0 && values[size - 1] == value) {
                return;
            }
            if (size == values.length) {
                values = Arrays.copyOf(values, size * 2);
            }
            values[size++] = value;
        }

        private int @NotNull [] sortedDistinct() {
            int[] sorted = Arrays.copyOf(values, size);
            Arrays.sort(sorted);
            return Arrays.stream(sorted).distinct().toArray();
        }
    }
}
