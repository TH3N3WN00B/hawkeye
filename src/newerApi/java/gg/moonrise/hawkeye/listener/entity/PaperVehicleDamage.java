package gg.moonrise.hawkeye.listener.entity;

import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.hawkeye.util.compat.ConditionalOnApi;
import gg.moonrise.hawkeye.util.compat.NewerApi;
import gg.moonrise.moss.spring.SpringComponent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.jetbrains.annotations.NotNull;

/** {@link VehicleDamage} on Paper 26.1.2 and later: a source named after the damage type ({@code #lava}). */
@SpringComponent
@ConditionalOnApi(NewerApi.VEHICLE_DESTROY_DAMAGE_SOURCE)
public final class PaperVehicleDamage implements VehicleDamage {

    @Override
    public @NotNull Actor cause(@NotNull VehicleDestroyEvent event) {
        return Actor.source(event.getDamageSource().getDamageType().getKey().getKey());
    }
}
