package gg.moonrise.hawkeye.entity.query;

import java.util.Set;
import org.jetbrains.annotations.NotNull;

/** Which servers' events a query reads. */
public sealed interface ServerScope {

    /** Only this server. */
    record Local() implements ServerScope {
    }

    /**
     * Named servers, on whichever shard holds them.
     *
     * @param names lower-case server names
     */
    record Named(@NotNull Set<String> names) implements ServerScope {

        /**
         * Copies the names.
         *
         * @param names lower-case server names
         */
        public Named {
            if (names.isEmpty()) {
                throw new IllegalArgumentException("a named scope needs at least one server");
            }
            names = Set.copyOf(names);
        }
    }

    /** Every server on every configured shard. */
    record Network() implements ServerScope {
    }
}
