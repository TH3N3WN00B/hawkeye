package gg.moonrise.hawkeye.entity.importer;

/** Why a source row was not imported. Every skipped row is counted under one reason. */
public enum SkipReason {
    /** Older than Hawkeye's epoch (2025-01-01Z), or stamped more than a week ahead of the import (a wrong clock). */
    BEFORE_EPOCH,
    /** An action Hawkeye has no event for. */
    NO_EQUIVALENT,
    /** A sign snapshot another event already carries. */
    FOLDED,
    /** A world, material, entity or position that cannot be resolved or stored. */
    UNKNOWN_REFERENCE
}
