package gg.moonrise.hawkeye.core.service.capture;

import gg.moonrise.hawkeye.entity.actor.Actor;
import java.util.Locale;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.projectiles.ProjectileSource;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Maps whatever caused an event to an {@link Actor}. A cause that traces back to a player is that
 * player (TNT they lit, an arrow they shot), so {@code /hawkeye rollback u:<griefer>} also undoes
 * their TNT damage; anything else is a {@code #source} named after its entity type.
 */
public final class Actors {

    /** Fire spreading or burning. */
    public static final Actor FIRE = Actor.source("fire");
    /** Flowing water. */
    public static final Actor WATER = Actor.source("water");
    /** Flowing lava. */
    public static final Actor LAVA = Actor.source("lava");
    /** A piston moving blocks. */
    public static final Actor PISTON = Actor.source("piston");
    /** Leaves decaying. */
    public static final Actor DECAY = Actor.source("decay");
    /** Natural changes without a more specific cause (growth, melting, forming, spreading). */
    public static final Actor NATURE = Actor.source("nature");
    /** An explosion with no known cause. */
    public static final Actor EXPLOSION = Actor.source("explosion");
    /** TNT with no known igniter. */
    public static final Actor TNT = Actor.source("tnt");
    /** A dispenser or dropper. */
    public static final Actor DISPENSER = Actor.source("dispenser");
    /** A hopper or hopper minecart. */
    public static final Actor HOPPER = Actor.source("hopper");
    /** Lightning. */
    public static final Actor LIGHTNING = Actor.source("lightning");
    /** The server console or a command block. */
    public static final Actor CONSOLE = Actor.source("console");

    private Actors() {
    }

    /**
     * A player.
     *
     * @param player the player
     * @return the actor
     */
    public static @NotNull Actor of(@NotNull Player player) {
        return Actor.player(player.getUniqueId(), player.getName());
    }

    /**
     * The actor behind an entity: the player itself, the player who lit TNT or shot a projectile, or a
     * {@code #source} named after the entity type.
     *
     * @param entity the entity, or {@code null} when unknown
     * @return the actor
     */
    public static @NotNull Actor of(@Nullable Entity entity) {
        return switch (entity) {
            case null -> EXPLOSION;
            case Player player -> of(player);
            case TNTPrimed tnt -> tnt.getSource() == null || tnt.getSource() == tnt ? TNT : ofCause(tnt.getSource(), TNT);
            case Projectile projectile -> ofShooter(projectile.getShooter(), projectile);
            default -> ofType(entity);
        };
    }

    private static @NotNull Actor ofCause(@NotNull Entity cause, @NotNull Actor fallback) {
        return cause instanceof Player || cause instanceof Projectile || cause instanceof TNTPrimed ? of(cause)
                : fallback;
    }

    private static @NotNull Actor ofShooter(@Nullable ProjectileSource shooter, @NotNull Projectile projectile) {
        return shooter instanceof Entity entity && entity != projectile ? of(entity) : ofType(projectile);
    }

    private static @NotNull Actor ofType(@NotNull Entity entity) {
        return Actor.source(entity.getType().getKey().getKey().toLowerCase(Locale.ROOT));
    }
}
