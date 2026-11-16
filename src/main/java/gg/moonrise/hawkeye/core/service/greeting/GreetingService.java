package gg.moonrise.hawkeye.core.service.greeting;

import gg.moonrise.engine.message.Message;
import gg.moonrise.hawkeye.config.HawkeyeConfig;
import gg.moonrise.hawkeye.config.HawkeyeConfigProvider;
import gg.moonrise.moss.spring.SpringComponent;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

/**
 * Decides how a player is greeted. Player-facing code (the join listener and {@code /hawkeye greet})
 * asks this service and renders the result; the service never sends anything itself.
 */
@SpringComponent
@RequiredArgsConstructor
public final class GreetingService {

    private final @NotNull HawkeyeConfigProvider configProvider;

    /**
     * The greeting for a player.
     *
     * @param firstJoin whether the player joins for the first time
     * @return the message to send, or {@link GreetingResult.Disabled} when greetings are off
     */
    public @NotNull GreetingResult greetingFor(boolean firstJoin) {
        HawkeyeConfig config = configProvider.get();
        if (!config.isGreetingsEnabled()) {
            return new GreetingResult.Disabled();
        }
        HawkeyeConfig.Messages messages = config.getMessages();
        return new GreetingResult.Greet(Message.of(firstJoin ? messages.getFirstJoin() : messages.getWelcomeBack()));
    }
}
