package gg.moonrise.hawkeye.config.env;

import java.util.Optional;
import org.jetbrains.annotations.NotNull;

/**
 * Where {@code ${NAME}} references in the configuration are looked up: the process environment
 * ({@link ProcessEnvironment}) on a server, a map in tests.
 */
@FunctionalInterface
public interface Environment {

    /**
     * The value of an environment variable.
     *
     * @param name the variable name
     * @return its value (possibly empty), or empty when it is not set
     */
    @NotNull Optional<String> variable(@NotNull String name);
}
