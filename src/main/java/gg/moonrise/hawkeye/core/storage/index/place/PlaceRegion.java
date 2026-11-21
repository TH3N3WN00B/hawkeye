package gg.moonrise.hawkeye.core.storage.index.place;

import gg.moonrise.hawkeye.util.codec.binary.ByteReader;
import gg.moonrise.hawkeye.util.codec.binary.ByteWriter;
import gg.moonrise.hawkeye.util.codec.binary.CodecException;
import gg.moonrise.hawkeye.util.codec.binary.Compression;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jetbrains.annotations.NotNull;

/**
 * One {@code place_region} row: per chunk of a region (32 x 32 chunks) that archived rows are in, the
 * segment ids holding rows in it and whether the chunk has a {@code place_chunk} row. The body is one
 * {@link Compression} frame of:
 * <pre>
 *   varint chunk count, then per chunk in chunk order:
 *     varint number inside the region ({@link PlaceKeys#indexInRegion}), as the delta from the previous one
 *     varint run count &lt;&lt; 1 | busy
 *     runs of segment ids ({@link IdRuns#write} without its count, coded against the previous chunk's first id)
 * </pre>
 * Not thread-safe.
 */
public final class PlaceRegion {

    private final int world;
    private final long region;
    private final TreeMap<Integer, IdRuns> chunks = new TreeMap<>();
    private final TreeSet<Integer> busy = new TreeSet<>();

    /**
     * An empty region.
     *
     * @param world  the world id
     * @param region the region key ({@link PlaceKeys#region})
     */
    public PlaceRegion(int world, long region) {
        this.world = world;
        this.region = region;
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
     * The region key.
     *
     * @return the key
     */
    public long region() {
        return region;
    }

    /**
     * The segment ids of a chunk, created empty when the chunk is not listed yet.
     *
     * @param index the chunk's number inside the region
     * @return the live set
     */
    public @NotNull IdRuns segmentsOf(int index) {
        return chunks.computeIfAbsent(checkIndex(index), ignored -> new IdRuns());
    }

    /**
     * The segment ids of a chunk, when it is listed.
     *
     * @param index the chunk's number inside the region
     * @return the live set
     */
    public @NotNull Optional<IdRuns> find(int index) {
        return Optional.ofNullable(chunks.get(index));
    }

    /**
     * Every listed chunk, by number.
     *
     * @return a read-only view
     */
    public @NotNull NavigableMap<Integer, IdRuns> chunks() {
        return Collections.unmodifiableNavigableMap(chunks);
    }

    /**
     * Whether a chunk has a {@code place_chunk} row.
     *
     * @param index the chunk's number inside the region
     * @return {@code true} when it has
     */
    public boolean busy(int index) {
        return busy.contains(index);
    }

    /**
     * Marks whether a listed chunk has a {@code place_chunk} row.
     *
     * @param index the chunk's number
     * @param value the new value
     */
    public void setBusy(int index, boolean value) {
        if (value) {
            if (!chunks.containsKey(index)) {
                throw new IllegalArgumentException("chunk " + index + " is not listed");
            }
            busy.add(index);
        } else {
            busy.remove(index);
        }
    }

    /**
     * Removes a segment id from every chunk.
     *
     * @param segment the segment id
     */
    public void removeEverywhere(long segment) {
        chunks.values().forEach(ids -> ids.remove(segment));
    }

    /**
     * Drops the segment ids below the oldest stored segment, and the chunks left with none.
     *
     * @param oldest the oldest stored segment id
     * @return the numbers of the busy chunks that were dropped (their {@code place_chunk} rows go too)
     */
    public @NotNull TreeSet<Integer> trim(long oldest) {
        TreeSet<Integer> droppedBusy = new TreeSet<>();
        Iterator<Map.Entry<Integer, IdRuns>> entries = chunks.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<Integer, IdRuns> entry = entries.next();
            entry.getValue().dropBelow(oldest);
            if (entry.getValue().isEmpty()) {
                if (busy.remove(entry.getKey())) {
                    droppedBusy.add(entry.getKey());
                }
                entries.remove();
            }
        }
        return droppedBusy;
    }

    /**
     * Whether no chunk is listed.
     *
     * @return {@code true} when the row can be deleted
     */
    public boolean isEmpty() {
        return chunks.isEmpty();
    }

    /**
     * The largest segment id listed.
     *
     * @return the id, 0 when empty
     */
    public long newest() {
        long newest = 0;
        for (IdRuns ids : chunks.values()) {
            if (!ids.isEmpty()) {
                newest = Math.max(newest, ids.max());
            }
        }
        return newest;
    }

    /**
     * Encodes the row's body.
     *
     * @param level the zstd level
     * @return one compressed frame
     */
    public byte @NotNull [] toBody(int level) {
        ByteWriter out = new ByteWriter(16 + chunks.size() * 6);
        out.writeVarLong(chunks.size());
        int previousIndex = 0;
        long previousFirst = 0;
        for (Map.Entry<Integer, IdRuns> entry : chunks.entrySet()) {
            IdRuns ids = entry.getValue();
            if (ids.isEmpty()) {
                throw new IllegalStateException("chunk " + entry.getKey() + " lists no segment");
            }
            out.writeVarLong(entry.getKey() - previousIndex);
            out.writeVarLong((long) ids.runCount() << 1 | (busy.contains(entry.getKey()) ? 1 : 0));
            ids.writeRuns(out, previousFirst);
            previousIndex = entry.getKey();
            previousFirst = ids.start(0);
        }
        return Compression.compressAt(out.toByteArray(), level);
    }

    /**
     * Decodes a row.
     *
     * @param world  the world id
     * @param region the region key
     * @param body   the body
     * @return the region
     * @throws CodecException when the body is corrupt
     */
    public static @NotNull PlaceRegion fromBody(int world, long region, byte @NotNull [] body) {
        byte[] raw = Compression.decompress(body, 0, body.length);
        ByteReader in = new ByteReader(raw);
        PlaceRegion out = new PlaceRegion(world, region);
        int count = in.readVarInt();
        if (count < 0 || count > PlaceKeys.REGION_CHUNKS) {
            throw new CodecException("region lists " + count + " chunks");
        }
        int index = 0;
        long previousFirst = 0;
        for (int i = 0; i < count; i++) {
            int delta = in.readVarInt();
            if (delta < 0 || i > 0 && delta == 0) {
                throw new CodecException("chunk numbers out of order");
            }
            index += delta;
            long head = in.readVarLong();
            IdRuns ids = IdRuns.readRuns(in, (int) (head >>> 1), previousFirst);
            if (ids.isEmpty()) {
                throw new CodecException("chunk " + index + " lists no segment");
            }
            out.chunks.put(checkIndex(index), ids);
            if ((head & 1) != 0) {
                out.busy.add(index);
            }
            previousFirst = ids.start(0);
        }
        if (in.hasRemaining()) {
            throw new CodecException("unread bytes after the region's chunks");
        }
        return out;
    }

    private static int checkIndex(int index) {
        if (index < 0 || index >= PlaceKeys.REGION_CHUNKS) {
            throw new CodecException("chunk number " + index + " outside a region");
        }
        return index;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PlaceRegion that && world == that.world && region == that.region
                && chunks.equals(that.chunks) && busy.equals(that.busy);
    }

    @Override
    public int hashCode() {
        return Long.hashCode(region) * 31 + chunks.hashCode();
    }

    @Override
    public @NotNull String toString() {
        return "PlaceRegion[world=" + world + ", region=" + region + ", chunks=" + chunks + ", busy=" + busy + "]";
    }
}
