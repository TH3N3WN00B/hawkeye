package gg.moonrise.hawkeye.core.service.network;

import java.util.List;
import org.jetbrains.annotations.NotNull;

/** Outcome of {@link NetworkJobService#dispatch}. */
public sealed interface DispatchOutcome {

    /**
     * A job was written for every target server.
     *
     * @param servers the target servers
     */
    record Dispatched(@NotNull List<String> servers) implements DispatchOutcome {

        /**
         * Copies the list.
         *
         * @param servers the target servers
         */
        public Dispatched {
            servers = List.copyOf(servers);
        }
    }

    /** No registered server matches the scope. */
    record NoServers() implements DispatchOutcome {
    }

    /** The home database is not ready. */
    record NotReady() implements DispatchOutcome {
    }

    /**
     * Writing a job failed.
     *
     * @param reason why
     */
    record Failed(@NotNull String reason) implements DispatchOutcome {
    }
}
