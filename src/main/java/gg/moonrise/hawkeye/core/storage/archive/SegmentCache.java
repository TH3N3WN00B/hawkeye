package gg.moonrise.hawkeye.core.storage.archive;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import gg.moonrise.hawkeye.core.sql.Database;
import gg.moonrise.hawkeye.core.storage.segment.Palettes;
import gg.moonrise.hawkeye.core.storage.segment.SegmentHeader;
import gg.moonrise.hawkeye.core.storage.segment.SharedColumns;
import gg.moonrise.hawkeye.util.codec.CodecException;
import gg.moonrise.moss.spring.SpringComponent;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;

/**
 * Parsed segment headers, decoded shared frames and palettes, by segment. A segment never changes after it is
 * written, and a rewrite inserts a new segment and deletes the old one ({@code AUTOINCREMENT} and
 * identity columns never hand out an id twice), so an entry never goes stale; deleted segments simply
 * age out. Entries are keyed by the {@link Database} object too, so two shards, or a database reopened
 * after a storage change, never share an entry.
 *
 * <p>Paging a lookup, a rollback after its preview and setting flags read the same segments again;
 * decoding a segment's shared frames costs about as much as decoding one of its units, so keeping them
 * saves a third to a half of a repeated read. Both caches are bounded by weight and thread-safe.
 */
@SpringComponent
public final class SegmentCache {

    /** The weight bound of the header cache, in bytes of the parsed headers' arrays (about). */
    public static final long HEADER_BYTES = 16L * 1024 * 1024;

    /** The weight bound of the shared-frames cache, in bytes of ids and palettes. */
    public static final long SHARED_BYTES = 32L * 1024 * 1024;

    /** The weight bound of the palette cache, in bytes of the palettes. */
    public static final long PALETTE_BYTES = 8L * 1024 * 1024;

    private final Cache<Key, SegmentHeader> headers = Caffeine.newBuilder()
            .maximumWeight(HEADER_BYTES)
            .weigher((Key key, SegmentHeader header) -> header.chunkBases().length * (Long.BYTES + Integer.BYTES)
                    + header.unitCount() * 128 + 512)
            .build();
    private final Cache<Key, SharedColumns> shared = Caffeine.newBuilder()
            .maximumWeight(SHARED_BYTES)
            .weigher((Key key, SharedColumns columns) -> columns.ids().length * Long.BYTES
                    + (columns.actorPalette().length + columns.typePalette().length) * Integer.BYTES + 64)
            .build();
    private final Cache<Key, Palettes> palettes = Caffeine.newBuilder()
            .maximumWeight(PALETTE_BYTES)
            .weigher((Key key, Palettes value) -> value.weight())
            .build();

    /**
     * A segment's parsed header, parsing and keeping it on a miss.
     *
     * @param database the shard's database
     * @param segment  the segment id
     * @param bytes    the stored header, parsed when it is not cached
     * @return the header
     * @throws CodecException when the header is corrupt
     */
    public @NotNull SegmentHeader header(@NotNull Database database, long segment, byte @NotNull [] bytes) {
        return headers.get(new Key(database, segment), key -> SegmentHeader.parse(bytes));
    }

    /**
     * A segment's decoded shared frames, when cached.
     *
     * @param database the shard's database
     * @param segment  the segment id
     * @return the columns, or empty
     */
    public @NotNull Optional<SharedColumns> shared(@NotNull Database database, long segment) {
        return Optional.ofNullable(shared.getIfPresent(new Key(database, segment)));
    }

    /**
     * Keeps a segment's decoded shared frames.
     *
     * @param database the shard's database
     * @param segment  the segment id
     * @param columns  the columns
     */
    public void keep(@NotNull Database database, long segment, @NotNull SharedColumns columns) {
        shared.put(new Key(database, segment), columns);
    }

    /**
     * A segment's palettes, when cached on their own or with the shared frames.
     *
     * @param database the shard's database
     * @param segment  the segment id
     * @return the palettes, or empty
     */
    public @NotNull Optional<Palettes> palettes(@NotNull Database database, long segment) {
        Key key = new Key(database, segment);
        Palettes kept = palettes.getIfPresent(key);
        if (kept != null) {
            return Optional.of(kept);
        }
        return Optional.ofNullable(shared.getIfPresent(key)).map(SharedColumns::palettes);
    }

    /**
     * Keeps a segment's palettes.
     *
     * @param database the shard's database
     * @param segment  the segment id
     * @param value    the palettes
     */
    public void keep(@NotNull Database database, long segment, @NotNull Palettes value) {
        palettes.put(new Key(database, segment), value);
    }

    /**
     * Drops what is kept about a segment (after it was deleted, so the memory is freed early).
     *
     * @param database the shard's database
     * @param segment  the segment id
     */
    public void forget(@NotNull Database database, long segment) {
        Key key = new Key(database, segment);
        headers.invalidate(key);
        shared.invalidate(key);
        palettes.invalidate(key);
    }

    /**
     * A cache key. {@link Database} compares by identity.
     *
     * @param database the shard's database
     * @param segment  the segment id
     */
    private record Key(@NotNull Database database, long segment) {
    }
}
