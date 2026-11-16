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
     * The place index: per region of 32 x 32 chunks, which segments hold rows in which chunk.
     *
     * @return the name
     */
    public @NotNull String placeRegion() {
        return prefix + "place_region";
    }

    /**
     * The place index of busy chunks: per position, the buckets of segments that touched it.
     *
     * @return the name
     */
    public @NotNull String placeChunk() {
        return prefix + "place_chunk";
    }

    /**
     * The place index's one-row state ({@code indexed_from}).
     *
     * @return the name
     */
    public @NotNull String placeState() {
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
