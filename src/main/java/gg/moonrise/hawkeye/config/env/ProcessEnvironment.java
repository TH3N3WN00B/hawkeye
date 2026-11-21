package gg.moonrise.hawkeye.config.env;

import gg.moonrise.moss.spring.SpringComponent;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;

/**
 * The server process's environment variables ({@link System#getenv(String)}). Not named
 * {@code SystemEnvironment}: Spring registers a bean called {@code systemEnvironment} itself.
 */
@SpringComponent
public final class ProcessEnvironment implements Environment {

    @Override
    public @NotNull Optional<String> variable(@NotNull String name) {
        return Optional.ofNullable(System.getenv(name));
    }
}
