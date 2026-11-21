package gg.moonrise.hawkeye.core.service.query;

import gg.moonrise.hawkeye.core.service.query.syntax.QueryError;
import gg.moonrise.hawkeye.core.service.query.syntax.QueryParameters;
import gg.moonrise.hawkeye.entity.query.LookupQuery;
import org.jetbrains.annotations.NotNull;

/** Outcome of {@link QueryBuilder#build}. */
public sealed interface BuildResult {

    /**
     * The query.
     *
     * @param query      the query to run
     * @param parameters the parameters it was built from (for flags)
     */
    record Built(@NotNull LookupQuery query, @NotNull QueryParameters parameters) implements BuildResult {
    }

    /**
     * The parameters cannot become a query for this sender.
     *
     * @param error what is wrong
     */
    record Invalid(@NotNull QueryError error) implements BuildResult {
    }
}
