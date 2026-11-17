package gg.moonrise.hawkeye.core.repository.place;

import gg.moonrise.hawkeye.util.codec.ByteReader;
import gg.moonrise.hawkeye.util.codec.ByteWriter;
import gg.moonrise.hawkeye.util.codec.CodecException;
import gg.moonrise.hawkeye.util.codec.Compression;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.function.IntPredicate;
import org.jetbrains.annotations.NotNull;

/**
 * One {@code block_segment} row: every position of a busy chunk that archived rows touched, each with
 * its newest {@link #EXACT_SEGMENTS} segment ids exactly and only what is older than those as buckets
 * ({@code segment id >> }{@link #BUCKET_SHIFT}). Nearly every position is in few segments, so a lookup
 * of a block reads exactly the segments that hold it; a block touched all the time stays bounded. The
 * body is one {@link Compression} frame of:
 * <pre>
 *   varint position count, then per position in ascending in-chunk key ({@link PlaceKeys#local}):
 *     varint key delta from the previous position (the key itself for the first)
 *     varint (exact run count &lt;&lt; 1) | hasOlder
 *     exact runs: {@link IdRuns#writeRuns} coded against the previous position's first exact id (0 for the first)
 *     if hasOlder: varint bucket run count (at least 1), then {@link IdRuns#writeRuns} coded against the
 *                  first bucket of the previous position that had older buckets (0 for the first such one)
 * </pre>
 * A position has at least one exact run, except after a rewrite removed every exact id of a position
 * that still has older buckets. Not thread-safe.
 */
public final class PlaceChunk {

    /** A bucket is 16 consecutive segment ids. */
    public static final int BUCKET_SHIFT = 4;

    /** How many of a position's newest segment ids are kept exactly. */
    public static final int EXACT_SEGMENTS = 64;

    private final int world;
    private final long chunk;
    private final int exactLimit;
    private final TreeMap<Integer, Touches> positions = new TreeMap<>();

    /**
     * The segments that touched some positions: exact ids, and buckets of the older ones.
     *
     * @param exact the exact segment ids
     * @param older the buckets of older segment ids
     */
    public record Touches(@NotNull IdRuns exact, @NotNull IdRuns older) {

        /**
         * Nothing.
         *
         * @return an empty value with its own sets
         */
        public static @NotNull Touches empty() {
            return new Touches(new IdRuns(), new IdRuns());
        }

        /**
         * Whether neither set holds anything.
         *
         * @return {@code true} when empty
         */
        public boolean isEmpty() {
            return exact.isEmpty() && older.isEmpty();
        }
    }

    /**
     * An empty row with the production exact limit.
     *
     * @param world the world id
     * @param chunk the chunk key
     */
    public PlaceChunk(int world, long chunk) {
        this(world, chunk, EXACT_SEGMENTS);
    }

    /**
     * An empty row with another exact limit, so tests demote ids with a handful of segments.
     *
     * @param world      the world id
     * @param chunk      the chunk key
     * @param exactLimit how many of a position's newest segment ids are kept exactly, at least 1
     */
    public PlaceChunk(int world, long chunk, int exactLimit) {
        if (exactLimit < 1) {
            throw new IllegalArgumentException("exact limit " + exactLimit);
        }
        this.world = world;
        this.chunk = chunk;
        this.exactLimit = exactLimit;
    }

    /**
     * The bucket of a segment.
     *
     * @param segment the segment id
     * @return the bucket
     */
    public static long bucket(long segment) {
        return segment >> BUCKET_SHIFT;
    }

    /**
     * The world id.
     *
     * @return the id
     */
    public int world() {
        return world;
    }

    /**
     * The chunk key.
     *
     * @return the key
     */
    public long chunk() {
        return chunk;
    }

    /**
     * Records that a segment touched a position. The result depends only on the set of ids added, not
     * on their order: a position keeps its largest ids exactly and the buckets of the rest.
     *
     * @param local   the in-chunk key
     * @param segment the segment id
     */
    public void add(int local, long segment) {
        Touches touches = positions.computeIfAbsent(local, ignored -> Touches.empty());
        IdRuns exact = touches.exact();
        if (exact.contains(segment)) {
            return;
        }
        if (exact.count() < exactLimit || segment > exact.min()) {
            exact.add(segment);
            while (exact.count() > exactLimit) {
                long smallest = exact.min();
                exact.remove(smallest);
                touches.older().add(bucket(smallest));
            }
        } else {
            touches.older().add(bucket(segment));
        }
    }

    /**
     * Drops a segment id from every position's exact ids (a rewritten segment). Buckets cannot drop one
     * id and keep it; the region row no longer lists it, which keeps it out of lookups.
     *
     * @param segment the segment id
     */
    public void remove(long segment) {
        Iterator<Map.Entry<Integer, Touches>> entries = positions.entrySet().iterator();
        while (entries.hasNext()) {
            Touches touches = entries.next().getValue();
            if (touches.exact().remove(segment) && touches.isEmpty()) {
                entries.remove();
            }
        }
    }

    /**
     * Every position with its segments.
     *
     * @return a read-only view
     */
    public @NotNull NavigableMap<Integer, Touches> positions() {
        return Collections.unmodifiableNavigableMap(positions);
    }

    /**
     * Drops the exact ids below the oldest stored segment and the buckets below its bucket, and the
     * positions left with nothing.
     *
     * @param oldest the oldest stored segment id
     */
    public void trim(long oldest) {
        long bound = bucket(oldest);
        Iterator<Map.Entry<Integer, Touches>> entries = positions.entrySet().iterator();
        while (entries.hasNext()) {
            Touches touches = entries.next().getValue();
            touches.exact().dropBelow(oldest);
            touches.older().dropBelow(bound);
            if (touches.isEmpty()) {
                entries.remove();
            }
        }
    }

    /**
     * Whether no position is left.
     *
     * @return {@code true} when the row can be deleted
     */
    public boolean isEmpty() {
        return positions.isEmpty();
    }

    /**
     * Encodes the row's body.
     *
     * @param level the zstd level
     * @return one compressed frame
     */
    public byte @NotNull [] toBody(int level) {
        ByteWriter out = new ByteWriter(16 + positions.size() * 6);
        out.writeVarLong(positions.size());
        int previous = 0;
        long previousExact = 0;
        long previousOlder = 0;
        for (Map.Entry<Integer, Touches> entry : positions.entrySet()) {
            IdRuns exact = entry.getValue().exact();
            IdRuns older = entry.getValue().older();
            out.writeVarLong(entry.getKey() - previous);
            out.writeVarLong(((long) exact.runCount() << 1) | (older.isEmpty() ? 0 : 1));
            exact.writeRuns(out, previousExact);
            if (!exact.isEmpty()) {
                previousExact = exact.start(0);
            }
            if (!older.isEmpty()) {
                out.writeVarLong(older.runCount());
                older.writeRuns(out, previousOlder);
                previousOlder = older.start(0);
            }
            previous = entry.getKey();
        }
        return Compression.compressAt(out.toByteArray(), level);
    }

    /**
     * Decodes a row with the production exact limit.
     *
     * @param world the world id
     * @param chunk the chunk key
     * @param body  the body
     * @return the row
     * @throws CodecException when the body is corrupt
     */
    public static @NotNull PlaceChunk fromBody(int world, long chunk, byte @NotNull [] body) {
        return fromBody(world, chunk, body, EXACT_SEGMENTS);
    }

    /**
     * Decodes a row.
     *
     * @param world      the world id
     * @param chunk      the chunk key
     * @param body       the body
     * @param exactLimit the exact limit the row was written with
     * @return the row
     * @throws CodecException when the body is corrupt or a position holds more exact ids than the limit
     */
    public static @NotNull PlaceChunk fromBody(int world, long chunk, byte @NotNull [] body, int exactLimit) {
        PlaceChunk out = new PlaceChunk(world, chunk, exactLimit);
        read(body, local -> true, Integer.MAX_VALUE, (local, exact, older) -> {
            if (exact.count() > exactLimit) {
                throw new CodecException("position " + local + " lists " + exact.count() + " exact ids, more than "
                        + exactLimit);
            }
            out.positions.put(local, new Touches(exact, older));
        });
        return out;
    }

    /**
     * The segments of the positions a predicate accepts, read straight from a body without building the
     * row (the read path of a lookup).
     *
     * @param body     the body
     * @param accepts  which in-chunk keys count
     * @param maxLocal no key above this is accepted: the read stops there (positions are sorted)
     * @return the union of their exact ids and the union of their older buckets
     * @throws CodecException when the body is corrupt
     */
    public static @NotNull Touches segmentsWhere(byte @NotNull [] body, @NotNull IntPredicate accepts, int maxLocal) {
        Touches out = Touches.empty();
        read(body, accepts, maxLocal, (local, exact, older) -> {
            out.exact().addAll(exact);
            out.older().addAll(older);
        });
        return out;
    }

    private static void read(byte @NotNull [] body, @NotNull IntPredicate accepts, int maxLocal, @NotNull Sink sink) {
        byte[] raw = Compression.decompress(body, 0, body.length);
        ByteReader in = new ByteReader(raw);
        int count = in.readVarInt();
        if (count < 0 || count > in.remaining()) {
            throw new CodecException("chunk row lists " + count + " positions in " + raw.length + " bytes");
        }
        int local = 0;
        long previousExact = 0;
        long previousOlder = 0;
        for (int i = 0; i < count; i++) {
            int delta = in.readVarInt();
            if (delta < 0 || i > 0 && delta == 0) {
                throw new CodecException("positions out of order");
            }
            local += delta;
            if (local > PlaceKeys.LOCAL_MASK) {
                throw new CodecException("in-chunk key " + local + " out of range");
            }
            if (local > maxLocal) {
                return;
            }
            int flags = in.readVarInt();
            if (flags < 0) {
                throw new CodecException("position " + local + " has a negative run count");
            }
            int exactRuns = flags >>> 1;
            boolean hasOlder = (flags & 1) != 0;
            if (exactRuns == 0 && !hasOlder) {
                throw new CodecException("position " + local + " lists no segment");
            }
            boolean wanted = accepts.test(local);
            IdRuns exact = new IdRuns();
            if (wanted) {
                exact = IdRuns.readRuns(in, exactRuns, previousExact);
                if (exactRuns > 0) {
                    previousExact = exact.start(0);
                }
            } else if (exactRuns > 0) {
                previousExact = IdRuns.skipRuns(in, exactRuns, previousExact);
            }
            IdRuns older = new IdRuns();
            if (hasOlder) {
                int olderRuns = in.readVarInt();
                if (olderRuns < 1) {
                    throw new CodecException("position " + local + " flags older buckets but lists " + olderRuns);
                }
                if (wanted) {
                    older = IdRuns.readRuns(in, olderRuns, previousOlder);
                    previousOlder = older.start(0);
                } else {
                    previousOlder = IdRuns.skipRuns(in, olderRuns, previousOlder);
                }
            }
            if (wanted) {
                sink.accept(local, exact, older);
            }
        }
        if (in.hasRemaining()) {
            throw new CodecException("unread bytes after the chunk row's positions");
        }
    }

    /** Receives a decoded position. */
    @FunctionalInterface
    private interface Sink {
        void accept(int local, @NotNull IdRuns exact, @NotNull IdRuns older);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PlaceChunk that && world == that.world && chunk == that.chunk
                && positions.equals(that.positions);
    }

    @Override
    public int hashCode() {
        return Long.hashCode(chunk) * 31 + positions.hashCode();
    }

    @Override
    public @NotNull String toString() {
        return "PlaceChunk[world=" + world + ", chunk=" + chunk + ", positions=" + positions.size() + "]";
    }
}
