package gg.moonrise.hawkeye.integration.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.api.HawkeyeApi;
import gg.moonrise.hawkeye.core.service.capture.block.BlockChanges;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.entity.payload.Payload;
import gg.moonrise.hawkeye.entity.query.Area;
import gg.moonrise.hawkeye.support.TestPlugins;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

public class HawkeyeApiServiceTest {

    private ServerMock server;
    private HawkeyeApi api;

    @BeforeEach
    public void setUp() {
        server = MockBukkit.mock();
        TestPlugins.load();
        TestPlugins.awaitStorage();
        api = server.getServicesManager().load(HawkeyeApi.class);
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void isRegisteredAndReady() {
        assertNotNull(api);
        assertTrue(api.ready());
    }

    @Test
    public void parsesParametersAroundAnOrigin() {
        World world = server.addSimpleWorld("world");
        HawkeyeApi.QueryParse.Parsed parsed = assertInstanceOf(HawkeyeApi.QueryParse.Parsed.class,
                api.parse("u:alex t:1h r:5", new Location(world, 10, 64, 10)));
        assertInstanceOf(Area.Box.class, parsed.query().area());
        HawkeyeApi.QueryParse.Invalid invalid = assertInstanceOf(HawkeyeApi.QueryParse.Invalid.class,
                api.parse("t:yesterday", null));
        assertTrue(invalid.problem().contains("yesterday"), invalid.problem());
    }

    @Test
    public void whatAPluginLogsCanBeLookedUp() throws Exception {
        World world = server.addSimpleWorld("world");
        api.logMessage(Action.COMMAND, Actor.source("myplugin"), new Location(world, 1, 70, 1), "/warp spawn");
        HawkeyeApi.QueryParse.Parsed parsed = assertInstanceOf(HawkeyeApi.QueryParse.Parsed.class,
                api.parse("u:#myplugin", null));
        List<StoredEvent> events = api.lookup(parsed.query(), 0, 10).get(20, TimeUnit.SECONDS);
        assertEquals(1, events.size());
        assertEquals(new Payload.Text("/warp spawn"), events.getFirst().payload().orElseThrow());
        assertEquals(1L, api.count(parsed.query()).get(20, TimeUnit.SECONDS));
    }

    @Test
    public void aLoggedRemovalKeepsTheContainersItems() throws Exception {
        World world = server.addSimpleWorld("world");
        Block block = world.getBlockAt(2, 64, 2);
        block.setType(Material.CHEST);
        ((Chest) block.getState()).getInventory().setItem(4, new ItemStack(Material.DIAMOND, 3));
        // What a plugin hands over: a snapshot taken before it removed the block.
        BlockState snapshot = block.getState(false);
        block.setType(Material.AIR);
        api.logRemoval(Actor.source("myplugin"), snapshot);

        StoredEvent event = removal();
        Payload.Inventory inventory = assertInstanceOf(Payload.Inventory.class, event.payload().orElseThrow());
        assertEquals(1, inventory.slots().size());
        assertEquals(4, inventory.slots().getFirst().index());
    }

    @Test
    public void aLoggedRemovalOfAWaterloggedBlockLeavesWater() throws Exception {
        World world = server.addSimpleWorld("world");
        Block block = world.getBlockAt(2, 64, 2);
        Stairs stairs = (Stairs) Material.OAK_STAIRS.createBlockData();
        stairs.setWaterlogged(true);
        BlockState state = block.getState();
        state.setBlockData(stairs);
        api.logRemoval(Actor.source("myplugin"), state);

        StoredEvent event = removal();
        assertEquals(stairs.getAsString(), event.subject());
        assertEquals(BlockChanges.WATER, event.secondary());
    }

    private @NotNull StoredEvent removal() throws Exception {
        HawkeyeApi.QueryParse.Parsed parsed = assertInstanceOf(HawkeyeApi.QueryParse.Parsed.class,
                api.parse("u:#myplugin a:-block", null));
        List<StoredEvent> events = api.lookup(parsed.query(), 0, 10).get(20, TimeUnit.SECONDS);
        assertEquals(1, events.size());
        assertEquals(Action.BLOCK_BREAK, events.getFirst().action());
        return events.getFirst();
    }
}
