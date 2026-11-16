package gg.moonrise.hawkeye.core.service.capture;

import gg.moonrise.hawkeye.entity.actor.Actor;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.jetbrains.annotations.NotNull;

/** Who gets the blame for a death. */
public final class KillCauses {

    private KillCauses() {
    }

    /**
     * The killer of an entity: the player credited with the kill, else whatever dealt the last damage
     * ({@link Actors#of(org.bukkit.entity.Entity)}, so an arrow or TNT counts for its player), else the
     * damage cause as a source ({@code #fall}, {@code #lava}, ...).
     *
     * @param victim the entity that died
     * @param source the damage source of the death
     * @return the actor to record
     */
    public static @NotNull Actor killer(@NotNull LivingEntity victim, @NotNull DamageSource source) {
        Player player = victim.getKiller();
        if (player != null) {
            return Actors.of(player);
        }
        EntityDamageEvent last = victim.getLastDamageCause();
        if (last instanceof EntityDamageByEntityEvent byEntity) {
            return Actors.of(byEntity.getDamager());
        }
        if (last != null) {
            return Actor.source(last.getCause().name());
        }
        if (source.getCausingEntity() != null) {
            return Actors.of(source.getCausingEntity());
        }
        return Actor.source(source.getDamageType().getKey().getKey());
    }
}
