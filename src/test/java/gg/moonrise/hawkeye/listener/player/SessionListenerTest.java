package gg.moonrise.hawkeye.listener.player;

import static org.junit.jupiter.api.Assertions.assertEquals;

import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.listener.Recorded;
import gg.moonrise.hawkeye.support.TestPlugins;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

public class SessionListenerTest {

    private ServerMock server;

    @BeforeEach
    public void setUp() {
        server = MockBukkit.mock();
        TestPlugins.load();
        server.addSimpleWorld("world");
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void aJoinAndAQuitAreRecorded() {
        PlayerMock player = server.addPlayer("Alex");
        player.disconnect();

        List<StoredEvent> events = Recorded.events(Action.JOIN, Action.QUIT);
        assertEquals(List.of(Action.JOIN, Action.QUIT), events.stream().map(StoredEvent::action).toList());
        assertEquals("Alex", events.getFirst().actor().name());
    }
}
