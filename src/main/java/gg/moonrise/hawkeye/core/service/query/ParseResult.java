package gg.moonrise.hawkeye.core.service.query;

import org.jetbrains.annotations.NotNull;

/** Outcome of {@link QuerySyntax#parse(String)}. */
public sealed interface ParseResult {

    /**
     * The list was read.
     *
     * @param parameters the parameters
     */
    record Parsed(@NotNull QueryParameters parameters) implements ParseResult {
    }

    /**
     * The list has an invalid token.
     *
     * @param error what is wrong
     */
    record Invalid(@NotNull QueryError error) implements ParseResult {
    }
}
