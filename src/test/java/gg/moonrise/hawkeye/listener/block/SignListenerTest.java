package gg.moonrise.hawkeye.listener.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.entity.payload.Payload;
import gg.moonrise.hawkeye.listener.Recorded;
import gg.moonrise.hawkeye.support.TestPlugins;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.event.block.SignChangeEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

public class SignListenerTest {

    private ServerMock server;
    private PlayerMock player;
    private Block block;

    @BeforeEach
    public void setUp() {
        server = MockBukkit.mock();
        TestPlugins.load();
        World world = server.addSimpleWorld("world");
        player = server.addPlayer("Alex");
        block = world.getBlockAt(0, 64, 0);
        block.setType(Material.OAK_SIGN);
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void anEditKeepsTheSidesBeforeAndAfter() {
        ((Sign) block.getState()).getSide(Side.BACK).line(0, Component.text("back"));
        List<Component> lines = List.of(Component.text("new"), Component.empty(), Component.empty(),
                Component.empty());
        server.getPluginManager().callEvent(new SignChangeEvent(block, player, lines, Side.FRONT));

        StoredEvent event = Recorded.only(Action.SIGN_EDIT);
        assertEquals("Alex", event.actor().name());
        Payload.Sign sign = (Payload.Sign) event.payload().orElseThrow();
        assertEquals(List.of(), sign.before().front());
        assertEquals(json(lines.getFirst()), sign.after().front().getFirst());
        assertEquals(4, sign.after().front().size());
        assertEquals(sign.before().back(), sign.after().back());
        assertEquals(json(Component.text("back")), sign.after().back().getFirst());
    }

    @Test
    public void closingTheEditorWithoutAChangeRecordsNothing() {
        List<Component> blank = List.of(Component.empty(), Component.empty(), Component.empty(), Component.empty());
        server.getPluginManager().callEvent(new SignChangeEvent(block, player, blank, Side.FRONT));

        assertTrue(Recorded.events(Action.SIGN_EDIT).isEmpty());
    }

    private static String json(Component line) {
        return GsonComponentSerializer.gson().serialize(line);
    }
}
