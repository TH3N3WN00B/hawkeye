package gg.moonrise.hawkeye.listener.entity;

import gg.moonrise.hawkeye.config.file.LogCategory;
import gg.moonrise.hawkeye.core.service.capture.EventRecorder;
import gg.moonrise.hawkeye.core.service.capture.KillCauses;
import gg.moonrise.moss.spring.SpringComponent;
import lombok.RequiredArgsConstructor;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Deaths ({@link LogCategory#KILLS}) of mobs and players, blamed as {@link KillCauses#killer} decides.
 * {@code PlayerDeathEvent} shares this event's handler list, so player deaths arrive here too.
 */
@SpringComponent
@RequiredArgsConstructor
public final class KillListener implements Listener {

    private final @NotNull EventRecorder recorder;

    /**
     * Records a death while the entity is still in the world.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(@NotNull EntityDeathEvent event) {
        LivingEntity victim = event.getEntity();
        if (recorder.enabled(LogCategory.KILLS, victim.getWorld())) {
            recorder.kill(KillCauses.killer(victim, event.getDamageSource()), victim);
        }
    }
}
