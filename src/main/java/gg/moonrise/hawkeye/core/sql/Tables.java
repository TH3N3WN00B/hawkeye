package gg.moonrise.hawkeye.core.sql;

import org.jetbrains.annotations.NotNull;

/**
 * The table names of one shard, all carrying the configured prefix.
 *
 * @param prefix the prefix, e.g. {@code hawkeye_}
 */
public record Tables(@NotNull String prefix) {

    /**
     * The schema version table.
     *
     * @return the name
     */
    public @NotNull String schemaVersion() {
        return prefix + "schema_version";
    }

    /**
     * The registered servers.
     *
     * @return the name
     */
    public @NotNull String server() {
        return prefix + "server";
    }

    /**
     * The world dictionary.
     *
     * @return the name
     */
    public @NotNull String world() {
        return prefix + "world";
    }

    /**
     * The actor dictionary.
     *
     * @return the name
     */
    public @NotNull String actor() {
        return prefix + "actor";
    }

    /**
     * The type dictionary (block states, materials, entity types).
     *
     * @return the name
     */
    public @NotNull String type() {
        return prefix + "type";
    }

    /**
     * The events.
     *
     * @return the name
     */
    public @NotNull String event() {
        return prefix + "event";
    }

    /**
     * The network jobs.
     *
     * @return the name
     */
    public @NotNull String job() {
        return prefix + "job";
    }

    /**
     * The maintenance leases.
     *
     * @return the name
     */
    public @NotNull String lease() {
        return prefix + "lease";
    }

    /**
     * The archive segments.
     *
     * @return the name
     */
    public @NotNull String segment() {
        return prefix + "segment";
    }

    /**
     * The rolled-back rows of archive segments (a segment itself never changes).
     *
     * @return the name
     */
    public @NotNull String segmentFlag() {
        return prefix + "segment_flag";
    }

    /**
     * The segment index of places: per region of 32 x 32 chunks, which segments hold rows in which chunk.
     *
     * @return the name
     */
    public @NotNull String regionSegment() {
        return prefix + "region_segment";
    }

    /**
     * The segment index of the blocks of busy chunks: per position, its newest segment ids exactly and
     * the older ones in buckets.
     *
     * @return the name
     */
    public @NotNull String blockSegment() {
        return prefix + "block_segment";
    }

    /**
     * The segment index of actors: per actor id, the segments that hold rows of that actor.
     *
     * @return the name
     */
    public @NotNull String actorSegment() {
        return prefix + "actor_segment";
    }

    /**
     * The segment index's one-row state ({@code indexed_from}).
     *
     * @return the name
     */
    public @NotNull String segmentIndex() {
        return prefix + "segment_index";
    }

    /**
     * The place index's region table of schema version 4, dropped by version 5.
     *
     * @return the name
     */
    public @NotNull String legacyPlaceRegion() {
        return prefix + "place_region";
    }

    /**
     * The place index's busy-chunk table of schema version 4, dropped by version 5.
     *
     * @return the name
     */
    public @NotNull String legacyPlaceChunk() {
        return prefix + "place_chunk";
    }

    /**
     * The place index's state table of schema version 4, dropped by version 5.
     *
     * @return the name
     */
    public @NotNull String legacyPlaceState() {
        return prefix + "place_state";
    }

    /**
     * The actor index of the development archive format, dropped by schema version 3.
     *
     * @return the name
     */
    public @NotNull String legacySegmentActor() {
        return prefix + "segment_actor";
    }
}
