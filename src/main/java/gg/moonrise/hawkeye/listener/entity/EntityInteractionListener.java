package gg.moonrise.hawkeye.listener.entity;

import gg.moonrise.hawkeye.config.file.LogCategory;
import gg.moonrise.hawkeye.core.service.capture.Actors;
import gg.moonrise.hawkeye.core.service.capture.EventRecorder;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.hawkeye.entity.payload.Payload;
import gg.moonrise.moss.spring.SpringComponent;
import io.papermc.paper.event.entity.EntityDyeEvent;
import io.papermc.paper.event.player.PlayerNameEntityEvent;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.AnimalTamer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTameEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.event.player.PlayerUnleashEntityEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Players handling entities ({@link LogCategory#ENTITY_INTERACTIONS}): shearing, naming with a name tag
 * (the payload is the new name as plain text), putting on and taking off a lead, taming (by the new
 * owner) and dyeing (the payload is the colour, e.g. {@code light_blue}). None of these is changed by a
 * rollback.
 */
@SpringComponent
@RequiredArgsConstructor
public final class EntityInteractionListener implements Listener {

    private final @NotNull EventRecorder recorder;

    /**
     * Records a sheep, mooshroom, snow golem or bogged sheared.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShear(@NotNull PlayerShearEntityEvent event) {
        record(Action.ENTITY_SHEAR, event.getPlayer(), event.getEntity(), null);
    }

    /**
     * Records an entity named with a name tag.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onName(@NotNull PlayerNameEntityEvent event) {
        Component name = event.getName();
        Payload payload = name == null ? null : new Payload.Text(PlainTextComponentSerializer.plainText()
                .serialize(name));
        record(Action.ENTITY_NAME, event.getPlayer(), event.getEntity(), payload);
    }

    /**
     * Records an entity put on a lead.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLeash(@NotNull PlayerLeashEntityEvent event) {
        record(Action.ENTITY_LEASH, event.getPlayer(), event.getEntity(), null);
    }

    /**
     * Records an entity taken off a lead by a player.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onUnleash(@NotNull PlayerUnleashEntityEvent event) {
        record(Action.ENTITY_UNLEASH, event.getPlayer(), event.getEntity(), null);
    }

    /**
     * Records an entity tamed, by its new owner.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTame(@NotNull EntityTameEvent event) {
        Entity entity = event.getEntity();
        if (recorder.enabled(LogCategory.ENTITY_INTERACTIONS, entity.getWorld())) {
            recorder.entity(Action.ENTITY_TAME, owner(event.getOwner()), entity, null);
        }
    }

    /**
     * Records an entity dyed by a player.
     *
     * @param event the event
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDye(@NotNull EntityDyeEvent event) {
        Player player = event.getPlayer();
        if (player != null) {
            record(Action.ENTITY_DYE, player, event.getEntity(),
                    new Payload.Text(event.getColor().name().toLowerCase(Locale.ROOT)));
        }
    }

    private void record(@NotNull Action action, @NotNull Player player, @NotNull Entity entity,
            @Nullable Payload payload) {
        if (recorder.enabled(LogCategory.ENTITY_INTERACTIONS, entity.getWorld())) {
            recorder.entity(action, Actors.of(player), entity, payload);
        }
    }

    private static @NotNull Actor owner(@NotNull AnimalTamer owner) {
        if (owner instanceof Entity entity) {
            return Actors.of(entity);
        }
        String name = owner.getName();
        return Actor.player(owner.getUniqueId(), name == null || name.isEmpty() ? owner.getUniqueId().toString()
                : name);
    }
}
