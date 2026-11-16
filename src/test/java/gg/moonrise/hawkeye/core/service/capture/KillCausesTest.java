package gg.moonrise.hawkeye.core.service.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Zombie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.entity.ZombieMock;

public class KillCausesTest {

    private ServerMock server;
    private ZombieMock zombie;
    private Creeper creeper;

    @BeforeEach
    public void setUp() {
        server = MockBukkit.mock();
        World world = server.addSimpleWorld("world");
        Location location = new Location(world, 0, 64, 0);
        zombie = (ZombieMock) world.spawn(location, Zombie.class);
        creeper = world.spawn(location, Creeper.class);
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void thePlayerCreditedWithTheKillComesFirst() {
        PlayerMock player = server.addPlayer("Alex");
        zombie.simulateDamage(1, creeper);
        zombie.setKiller(player);
        assertEquals("Alex", KillCauses.killer(zombie, fall()).name());
    }

    @Test
    public void elseWhateverDealtTheLastDamage() {
        zombie.simulateDamage(1, creeper);
        assertEquals("#creeper", KillCauses.killer(zombie, fall()).name());
    }

    @Test
    public void elseTheCauseOfTheLastDamage() {
        zombie.simulateDamage(1, fall());
        assertEquals("#custom", KillCauses.killer(zombie, fall()).name());
    }

    @Test
    public void withoutAnyDamageTheDeathsOwnSourceDecides() {
        DamageSource byCreeper = DamageSource.builder(DamageType.MOB_ATTACK).withCausingEntity(creeper)
                .withDirectEntity(creeper).build();
        assertEquals("#creeper", KillCauses.killer(zombie, byCreeper).name());
        assertEquals("#fall", KillCauses.killer(zombie, fall()).name());
    }

    private static DamageSource fall() {
        return DamageSource.builder(DamageType.FALL).build();
    }
}
