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
 * One {@code place_chunk} row: every position of a busy chunk that archived rows touched, each with the
 * buckets ({@code segment id >> }{@link #BUCKET_SHIFT}) it was touched in. The body is one
 * {@link Compression} frame of:
 * <pre>
 *   varint position count, then per position in position order:
 *     varint in-chunk key ({@link PlaceKeys#local}) as the delta from the previous one
 *     varint bucket run count, then the runs ({@link IdRuns#writeRuns}, coded against the previous
 *     position's first bucket)
 * </pre>
 * Not thread-safe.
 */
public final class PlaceChunk {

    /** A bucket is 16 consecutive segment ids. */
    public static final int BUCKET_SHIFT = 4;

    private final int world;
    private final long chunk;
    private final TreeMap<Integer, IdRuns> positions = new TreeMap<>();

    /**
     * An empty row.
     *
     * @param world the world id
     * @param chunk the chunk key
     */
    public PlaceChunk(int world, long chunk) {
        this.world = world;
        this.chunk = chunk;
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
     * Records that a segment touched a position.
     *
     * @param local   the in-chunk key
     * @param segment the segment id
     */
    public void add(int local, long segment) {
        positions.computeIfAbsent(local, ignored -> new IdRuns()).add(bucket(segment));
    }

    /**
     * Every position with its buckets.
     *
     * @return a read-only view
     */
    public @NotNull NavigableMap<Integer, IdRuns> positions() {
        return Collections.unmodifiableNavigableMap(positions);
    }

    /**
     * Drops the buckets below the oldest stored segment's bucket, and the positions left with none.
     *
     * @param oldest the oldest stored segment id
     */
    public void trim(long oldest) {
        long bound = bucket(oldest);
        Iterator<Map.Entry<Integer, IdRuns>> entries = positions.entrySet().iterator();
        while (entries.hasNext()) {
            IdRuns buckets = entries.next().getValue();
            buckets.dropBelow(bound);
            if (buckets.isEmpty()) {
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
        ByteWriter out = new ByteWriter(16 + positions.size() * 5);
        out.writeVarLong(positions.size());
        int previous = 0;
        long previousFirst = 0;
        for (Map.Entry<Integer, IdRuns> entry : positions.entrySet()) {
            IdRuns buckets = entry.getValue();
            out.writeVarLong(entry.getKey() - previous);
            out.writeVarLong(buckets.runCount());
            buckets.writeRuns(out, previousFirst);
            previous = entry.getKey();
            previousFirst = buckets.start(0);
        }
        return Compression.compressAt(out.toByteArray(), level);
    }

    /**
     * Decodes a row.
     *
     * @param world the world id
     * @param chunk the chunk key
     * @param body  the body
     * @return the row
     * @throws CodecException when the body is corrupt
     */
    public static @NotNull PlaceChunk fromBody(int world, long chunk, byte @NotNull [] body) {
        PlaceChunk out = new PlaceChunk(world, chunk);
        read(body, local -> true, Integer.MAX_VALUE, (local, buckets) -> out.positions.put(local, buckets));
        return out;
    }

    /**
     * The buckets of the positions a predicate accepts, read straight from a body without building the
     * row (the read path of a lookup).
     *
     * @param body     the body
     * @param accepts  which in-chunk keys count
     * @param maxLocal no key above this is accepted: the read stops there (positions are sorted)
     * @return the union of their buckets
     * @throws CodecException when the body is corrupt
     */
    public static @NotNull IdRuns bucketsWhere(byte @NotNull [] body, @NotNull IntPredicate accepts, int maxLocal) {
        IdRuns out = new IdRuns();
        read(body, accepts, maxLocal, (local, buckets) -> out.addAll(buckets));
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
        long previousFirst = 0;
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
            int runs = in.readVarInt();
            if (runs == 0) {
                throw new CodecException("position " + local + " lists no bucket");
            }
            if (accepts.test(local)) {
                IdRuns buckets = IdRuns.readRuns(in, runs, previousFirst);
                sink.accept(local, buckets);
                previousFirst = buckets.start(0);
            } else {
                previousFirst = IdRuns.skipRuns(in, runs, previousFirst);
            }
        }
        if (in.hasRemaining()) {
            throw new CodecException("unread bytes after the chunk row's positions");
        }
    }

    /** Receives a decoded position. */
    @FunctionalInterface
    private interface Sink {
        void accept(int local, @NotNull IdRuns buckets);
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
