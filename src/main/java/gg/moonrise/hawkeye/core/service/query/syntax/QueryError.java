package gg.moonrise.hawkeye.core.service.query.syntax;

import org.jetbrains.annotations.NotNull;

/**
 * Why a parameter list or the query built from it is invalid. Player-facing code renders each kind
 * with its {@code messages.yml} entry ({@code config.message.QueryMessages}); {@code value} fills the
 * {@code <value>} placeholder.
 *
 * @param kind  what is wrong
 * @param value the offending token or the limit involved
 */
public record QueryError(@NotNull Kind kind, @NotNull String value) {

    /** The kinds of problem. */
    public enum Kind {
        /** A token with an unknown prefix. */
        UNKNOWN_PARAMETER,
        /** A {@code t:} value that is not a duration or range. */
        INVALID_TIME,
        /** An {@code r:} value that is not a number, {@code NxM}, {@code #world} or {@code #global}. */
        INVALID_RADIUS,
        /** An {@code a:} value that is not an action. */
        UNKNOWN_ACTION,
        /** An {@code i:} or {@code e:} value that is no block, item or entity type. */
        UNKNOWN_TYPE,
        /** An {@code s:} value that is not a valid server name. */
        INVALID_SERVER,
        /** A parameter that is empty after its prefix ({@code u:}). */
        EMPTY_VALUE,
        /** A radius beyond the sender's limit; the value is the limit. */
        RADIUS_TOO_LARGE,
        /** A radius from a sender with no position (the console). */
        RADIUS_NEEDS_POSITION,
        /** {@code r:#world} naming a world this server does not have. */
        UNKNOWN_WORLD,
        /** {@code s:} without the network permission. */
        NETWORK_NOT_ALLOWED,
        /** An area ({@code r:}) combined with other servers ({@code s:}). */
        AREA_WITH_SERVERS
    }
}
