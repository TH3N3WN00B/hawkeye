package gg.moonrise.hawkeye.entity.query;

import java.util.Set;
import org.jetbrains.annotations.NotNull;

/** Which worlds of one shard a query reads, derived from its {@code ServerScope}. */
public sealed interface ShardScope {

    /**
     * The worlds of some world scopes (this server's, or the named servers').
     *
     * @param scopes the world scopes
     */
    record Scopes(@NotNull Set<String> scopes) implements ShardScope {

        /**
         * Copies the scopes.
         *
         * @param scopes the world scopes
         */
        public Scopes {
            scopes = Set.copyOf(scopes);
        }
    }

    /** Every world of the shard. */
    record All() implements ShardScope {
    }
}
