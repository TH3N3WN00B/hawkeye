package gg.moonrise.hawkeye.core.service.greeting;

import gg.moonrise.engine.message.Message;
import org.jetbrains.annotations.NotNull;

/** Outcome of {@link GreetingService#greetingFor(boolean)}. */
public sealed interface GreetingResult {

    /** Greet the player with {@code message}; it takes a {@code <player>} placeholder. */
    record Greet(@NotNull Message message) implements GreetingResult {
    }

    /** Greetings are turned off in the configuration. */
    record Disabled() implements GreetingResult {
    }
}
