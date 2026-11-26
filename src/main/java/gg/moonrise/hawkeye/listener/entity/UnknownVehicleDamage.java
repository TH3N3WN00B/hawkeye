package gg.moonrise.hawkeye.listener.entity;

import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.hawkeye.util.compat.ConditionalOnMissingApi;
import gg.moonrise.hawkeye.util.compat.NewerApi;
import gg.moonrise.moss.spring.SpringComponent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.jetbrains.annotations.NotNull;

/** {@link VehicleDamage} before Paper 26.1.2, whose vehicle destroy event carries no damage source. */
@SpringComponent
@ConditionalOnMissingApi(NewerApi.VEHICLE_DESTROY_DAMAGE_SOURCE)
public final class UnknownVehicleDamage implements VehicleDamage {

    @Override
    public @NotNull Actor cause(@NotNull VehicleDestroyEvent event) {
        return EntityRemoveListener.UNKNOWN;
    }
}
