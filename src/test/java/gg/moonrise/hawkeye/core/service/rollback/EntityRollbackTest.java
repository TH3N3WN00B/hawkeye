package gg.moonrise.hawkeye.core.service.rollback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.command.RollbackTestSupport;
import gg.moonrise.hawkeye.core.service.capture.Actors;
import gg.moonrise.hawkeye.core.service.capture.EventRecorder;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.query.Area;
import gg.moonrise.hawkeye.entity.query.LookupQuery;
import gg.moonrise.hawkeye.support.TestPlugins;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Zombie;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** Entity rollbacks from the recorded events to the world, through the rollback service. */
public class EntityRollbackTest {

    private ServerMock server;
    private World world;
    private PlayerMock alex;
    private EventRecorder recorder;
    private RollbackService rollbacks;

    @BeforeEach
    public void setUp() {
        server = MockBukkit.mock();
        TestPlugins.load();
        TestPlugins.awaitStorage();
        world = server.addSimpleWorld("world");
        alex = server.addPlayer("Alex");
        recorder = TestPlugins.bean(EventRecorder.class);
        rollbacks = TestPlugins.bean(RollbackService.class);
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void aRollbackRemovesASpawnedMobAndARestoreLeavesItAlone() {
        Zombie zombie = world.spawn(new Location(world, 4.5, 64, 4.5), Zombie.class);
        recorder.entitySpawn(Actors.of(alex), zombie);
        TestPlugins.flush();

        RollbackOutcome.Applied rollback = run(RollbackMode.ROLLBACK);

        assertFalse(zombie.isValid());
        assertEquals(1, rollback.events());
        assertEquals(1, rollback.entities());
        assertInstanceOf(RollbackOutcome.NothingToDo.class, outcome(RollbackMode.RESTORE));
    }

    @Test
    public void aRollbackTakesAnItemOutOfTheFrameItWasPutInto() {
        ItemFrame frame = world.spawn(new Location(world, 6.5, 64.5, 6.97), ItemFrame.class);
        frame.setItem(new ItemStack(Material.DIAMOND), false);
        recorder.container(Action.CONTAINER_DEPOSIT, Actors.of(alex), frame.getLocation(),
                new ItemStack(Material.DIAMOND), 1);
        TestPlugins.flush();

        RollbackOutcome.Applied rollback = run(RollbackMode.ROLLBACK);

        assertTrue(frame.getItem().isEmpty());
        assertEquals(1, rollback.items());
        RollbackOutcome.Applied restore = run(RollbackMode.RESTORE);
        assertEquals(Material.DIAMOND, frame.getItem().getType());
        assertEquals(1, restore.items());
    }

    private RollbackOutcome.Applied run(RollbackMode mode) {
        return assertInstanceOf(RollbackOutcome.Applied.class, outcome(mode));
    }

    private RollbackOutcome outcome(RollbackMode mode) {
        RollbackIssuer issuer = RollbackIssuer.of(server.getConsoleSender());
        CompletableFuture<RollbackOutcome> outcome = rollbacks.run(new RollbackRequest(mode,
                LookupQuery.at(new Area.World("world")), issuer, false, RollbackListener.NONE));
        RollbackTestSupport.tickUntil(server, outcome::isDone, () -> mode + " did not finish");
        return outcome.join();
    }
}
