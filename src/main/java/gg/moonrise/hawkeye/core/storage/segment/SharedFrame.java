package gg.moonrise.hawkeye.core.storage.segment;

/** The shared frames at the start of a segment body, in body order. */
public enum SharedFrame {
    /** Per row: the slot delta. */
    TIME,
    /** Per row: the node index (only with several nodes). */
    NODE,
    /** Per row: the rank difference from the expected rank (only when some difference is not 0). */
    RANK,
    /** The actor palette, then the type palette. */
    PALETTES
}
