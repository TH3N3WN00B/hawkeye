package gg.moonrise.hawkeye.config.env;

import java.util.List;
import org.jetbrains.annotations.NotNull;

/** A configuration value after its {@code ${NAME}} references were looked up ({@link EnvReferences}). */
public sealed interface EnvValue {

    /**
     * Every reference was replaced. {@link #toString()} never shows the value.
     *
     * @param value       the resolved text
     * @param substituted whether any part of it came from an environment variable
     */
    record Resolved(@NotNull String value, boolean substituted) implements EnvValue {

        @Override
        public @NotNull String toString() {
            return "Resolved[substituted=" + substituted + "]";
        }
    }

    /**
     * At least one referenced variable is not set.
     *
     * @param variables the names of the unset variables, in order of first reference
     */
    record Unset(@NotNull List<String> variables) implements EnvValue {

        /**
         * Copies the list.
         *
         * @param variables the unset variables
         */
        public Unset {
            variables = List.copyOf(variables);
        }
    }
}
