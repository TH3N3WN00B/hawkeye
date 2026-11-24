package gg.moonrise.hawkeye.core.service.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.core.service.capture.container.InventoryDiff;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.entity.payload.Payload;
import gg.moonrise.hawkeye.listener.Recorded;
import gg.moonrise.hawkeye.support.TestPlugins;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

public class EventRecorderTest {

    private EventRecorder recorder;
    private Location at;

    @BeforeEach
    public void setUp() {
        ServerMock server = MockBukkit.mock();
        TestPlugins.load();
        World world = server.addSimpleWorld("world");
        recorder = TestPlugins.bean(EventRecorder.class);
        at = new Location(world, 1.5, 64, -2.5);
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void virtualMovesAreRecordedWithTheirInventory() {
        recorder.virtualChanges(Actor.source("test"), at, "ender_chest", List.of(
                new InventoryDiff.Delta(new ItemStack(Material.DIAMOND), 3),
                new InventoryDiff.Delta(new ItemStack(Material.STONE), -2)));
        recorder.virtual(Action.VIRTUAL_DEPOSIT, Actor.source("test"), at, "ender_chest",
                new ItemStack(Material.DIAMOND), 0);
        recorder.virtual(Action.VIRTUAL_DEPOSIT, Actor.source("test"), at, "ender_chest", ItemStack.empty(), 4);

        StoredEvent deposit = Recorded.only(Action.VIRTUAL_DEPOSIT);
        assertEquals("minecraft:diamond", deposit.subject());
        assertEquals(3, deposit.amount());
        assertEquals(List.of(1, 64, -3), List.of(deposit.x(), deposit.y(), deposit.z()));
        assertTrue(deposit.payload().orElseThrow() instanceof Payload.VirtualItem item
                && item.container().equals("ender_chest"));
        assertEquals(2, Recorded.only(Action.VIRTUAL_WITHDRAW).amount());
    }

    @Test
    public void containerAndVirtualActionsAreNotInterchangeable() {
        ItemStack item = new ItemStack(Material.DIAMOND);
        assertThrows(IllegalArgumentException.class, () -> recorder.virtual(Action.CONTAINER_DEPOSIT,
                Actor.source("test"), at, "ender_chest", item, 1));
        assertThrows(IllegalArgumentException.class, () -> recorder.container(Action.VIRTUAL_WITHDRAW,
                Actor.source("test"), at, item, 1));
    }
}
