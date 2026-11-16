package gg.moonrise.hawkeye.listener.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.core.service.capture.WrittenBooks;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.entity.payload.Payload;
import gg.moonrise.hawkeye.listener.Recorded;
import gg.moonrise.hawkeye.support.TestHawkeyePlugin;
import gg.moonrise.hawkeye.support.TestPlugins;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.inventory.meta.BookMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

public class BookListenerTest {

    private ServerMock server;
    private TestHawkeyePlugin plugin;
    private PlayerMock player;

    @BeforeEach
    public void setUp() {
        server = MockBukkit.mock();
        plugin = TestPlugins.load();
        World world = server.addSimpleWorld("world");
        player = server.addPlayer("Alex");
        player.teleport(new Location(world, -3, 65, 11));
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void anEditRecordsThePagesWhereThePlayerStands() {
        edit(WrittenBooks.book(null, "shopping list", "eggs\nflour"), false);

        StoredEvent event = Recorded.only(Action.BOOK_EDIT);
        assertEquals("Alex", event.actor().name());
        assertEquals(new Payload.Text("shopping list | eggs flour"), event.payload().orElseThrow());
        assertEquals(-3, event.x());
        assertEquals(11, event.z());
    }

    @Test
    public void signingRecordsTheTitleFirst() {
        edit(WrittenBooks.book("Rules", "be nice"), true);

        assertEquals(new Payload.Text("title: Rules | be nice"), Recorded.only(Action.BOOK_EDIT).payload()
                .orElseThrow());
    }

    @Test
    public void aDisabledCategoryRecordsNothing() {
        TestPlugins.writeConfig(plugin, "logging:\n  books: false\n");
        TestPlugins.reload();
        edit(WrittenBooks.book(null, "secret"), false);

        assertTrue(Recorded.events(Action.BOOK_EDIT).isEmpty());
    }

    private void edit(BookMeta book, boolean signing) {
        server.getPluginManager().callEvent(new PlayerEditBookEvent(player, 0, WrittenBooks.book(null), book,
                signing));
    }
}
