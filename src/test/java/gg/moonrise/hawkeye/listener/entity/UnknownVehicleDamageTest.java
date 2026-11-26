package gg.moonrise.hawkeye.listener.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import gg.moonrise.hawkeye.core.service.capture.CauseTracker;
import gg.moonrise.hawkeye.core.service.capture.EventRecorder;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.listener.Recorded;
import gg.moonrise.hawkeye.support.TestPlugins;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.minecart.RideableMinecart;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

/**
 * The fallback for servers before Paper 26.1.2, built here because MockBukkit's event names the damage
 * and the plugin registers {@code PaperVehicleDamage}.
 */
public class UnknownVehicleDamageTest {

    private World world;
    private EntityRemoveListener listener;

    @BeforeEach
    public void setUp() {
        ServerMock server = MockBukkit.mock();
        TestPlugins.load();
        world = server.addSimpleWorld("world");
        listener = new EntityRemoveListener(TestPlugins.bean(EventRecorder.class), TestPlugins.bean(CauseTracker.class),
                new UnknownVehicleDamage());
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void aVehicleDestroyedWithoutAnAttackerIsRemovedByAnUnknownSource() {
        RideableMinecart burnt = world.spawn(new Location(world, 9.5, 64, 8.5), RideableMinecart.class);

        listener.onVehicleDestroy(new VehicleDestroyEvent(burnt, DamageSource.builder(DamageType.LAVA).build(), null));

        assertEquals(EntityRemoveListener.UNKNOWN.name(), Recorded.only(Action.ENTITY_REMOVE).actor().name());
    }
}
