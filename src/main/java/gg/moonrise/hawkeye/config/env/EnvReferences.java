package gg.moonrise.hawkeye.config.env;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;

/**
 * Replaces {@code ${NAME}} references in a configuration value with environment variables. A reference
 * may stand anywhere in the text and more than once ({@code redis://:${HAWKEYE_REDIS_PASSWORD}@host});
 * {@code NAME} is {@code [A-Za-z_][A-Za-z0-9_]*}. A substituted value is not expanded again, a variable
 * that is set but empty is a valid value, and text that is not a well-formed reference stays as it is.
 * There is no default syntax and no escape: a literal value that contains {@code ${NAME}} itself has to
 * come from a variable.
 */
public final class EnvReferences {

    /** One reference; group 1 is the variable name. */
    public static final Pattern REFERENCE = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    private EnvReferences() {
    }

    /**
     * Resolves every reference in a value.
     *
     * @param text        the value as written in the configuration
     * @param environment where variables are looked up
     * @return the resolved value, or the names of the referenced variables that are not set
     */
    public static @NotNull EnvValue resolve(@NotNull String text, @NotNull Environment environment) {
        Matcher matcher = REFERENCE.matcher(text);
        StringBuilder resolved = new StringBuilder(text.length());
        Set<String> unset = new LinkedHashSet<>();
        boolean substituted = false;
        while (matcher.find()) {
            String name = matcher.group(1);
            Optional<String> value = environment.variable(name);
            if (value.isEmpty()) {
                unset.add(name);
                matcher.appendReplacement(resolved, "");
                continue;
            }
            matcher.appendReplacement(resolved, Matcher.quoteReplacement(value.get()));
            substituted = true;
        }
        matcher.appendTail(resolved);
        if (!unset.isEmpty()) {
            return new EnvValue.Unset(List.copyOf(unset));
        }
        return new EnvValue.Resolved(resolved.toString(), substituted);
    }

    /**
     * The variables a value references that are not set.
     *
     * @param text        the value as written in the configuration
     * @param environment where variables are looked up
     * @return the unset variable names; empty when the value resolves
     */
    public static @NotNull List<String> unset(@NotNull String text, @NotNull Environment environment) {
        return switch (resolve(text, environment)) {
            case EnvValue.Resolved resolved -> List.of();
            case EnvValue.Unset missing -> missing.variables();
        };
    }

    /**
     * Resolves a value whose variables were validated to be set.
     *
     * @param text        the value as written in the configuration
     * @param environment where variables are looked up
     * @param key         the configuration key, for the exception
     * @return the resolved value
     * @throws IllegalStateException when a referenced variable is not set; the message names the variable
     *                               and the key, never a value
     */
    public static @NotNull EnvValue.Resolved require(@NotNull String text, @NotNull Environment environment,
            @NotNull String key) {
        return switch (resolve(text, environment)) {
            case EnvValue.Resolved resolved -> resolved;
            case EnvValue.Unset missing -> throw new IllegalStateException(String.join("; ",
                    missing.variables().stream().map(variable -> unsetMessage(key, variable)).toList()));
        };
    }

    /**
     * The problem text for an unset variable.
     *
     * @param key      the configuration key
     * @param variable the unset variable
     * @return e.g. {@code storage.password: the environment variable HAWKEYE_DB_PASSWORD is not set}
     */
    public static @NotNull String unsetMessage(@NotNull String key, @NotNull String variable) {
        return key + ": the environment variable " + variable + " is not set";
    }
}
