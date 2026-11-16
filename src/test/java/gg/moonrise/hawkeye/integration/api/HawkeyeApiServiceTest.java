package gg.moonrise.hawkeye.integration.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.api.HawkeyeApi;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.entity.payload.Payload;
import gg.moonrise.hawkeye.entity.query.Area;
import gg.moonrise.hawkeye.support.TestPlugins;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.bukkit.Location;
import org.bukkit.World;
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
}
