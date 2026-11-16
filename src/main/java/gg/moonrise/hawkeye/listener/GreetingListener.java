package gg.moonrise.hawkeye.listener;

import gg.moonrise.hawkeye.core.service.greeting.GreetingResult;
import gg.moonrise.hawkeye.core.service.greeting.GreetingService;
import gg.moonrise.moss.spring.SpringComponent;
import lombok.RequiredArgsConstructor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Greets players when they join. A {@link Listener} bean is registered by plugin-engine at enable;
 * the join event runs on the thread that owns the player, so the message needs no scheduling.
 */
@SpringComponent
@RequiredArgsConstructor
public final class GreetingListener implements Listener {

    private final @NotNull GreetingService greetingService;

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(@NotNull PlayerJoinEvent event) {
        Player player = event.getPlayer();
        switch (greetingService.greetingFor(!player.hasPlayedBefore())) {
            case GreetingResult.Greet greet -> greet.message().send(player,
                    Placeholder.unparsed("player", player.getName()));
            case GreetingResult.Disabled _ -> {
                // Greetings are off; nothing to send.
            }
        }
    }
}
