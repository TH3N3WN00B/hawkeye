package gg.moonrise.hawkeye.listener.entity;

import gg.moonrise.hawkeye.entity.actor.Actor;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.jetbrains.annotations.NotNull;

/**
 * What destroyed a boat or minecart that no entity attacked. Paper names the damage from 26.1.2
 * ({@code PaperVehicleDamage}); before, {@link UnknownVehicleDamage} has only
 * {@link EntityRemoveListener#UNKNOWN}.
 */
public interface VehicleDamage {

    /**
     * The source behind a destroyed vehicle without an attacker.
     *
     * @param event the event
     * @return a source named after the damage type, or {@link EntityRemoveListener#UNKNOWN}
     */
    @NotNull Actor cause(@NotNull VehicleDestroyEvent event);
}
