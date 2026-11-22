package gg.moonrise.hawkeye.listener.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.entity.payload.Payload;
import gg.moonrise.hawkeye.listener.Recorded;
import gg.moonrise.hawkeye.support.TestHawkeyePlugin;
import gg.moonrise.hawkeye.support.TestPlugins;
import io.papermc.paper.event.player.PlayerSignCommandPreprocessEvent;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.command.CommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.RemoteServerCommandEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

public class ChatListenerTest {

    private ServerMock server;
    private TestHawkeyePlugin plugin;
    private World world;
    private PlayerMock player;

    @BeforeEach
    public void setUp() {
        server = MockBukkit.mock();
        plugin = TestPlugins.load();
        world = server.addSimpleWorld("world");
        player = server.addPlayer("Alex");
        player.teleport(new Location(world, 7, 80, -7));
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void chatIsRecordedFromThePlayersThreadWhereTheyStand() {
        player.chat("hello there");
        server.getScheduler().waitAsyncEventsFinished();
        server.getScheduler().performOneTick();

        StoredEvent event = Recorded.only(Action.CHAT);
        assertEquals("Alex", event.actor().name());
        assertEquals(new Payload.Text("hello there"), event.payload().orElseThrow());
        assertEquals(7, event.x());
        assertEquals(80, event.y());
    }

    @Test
    public void disabledChatSchedulesNothing() {
        TestPlugins.writeConfig(plugin, "logging:\n  chat: false\n");
        TestPlugins.reload();
        player.chat("hello there");
        server.getScheduler().waitAsyncEventsFinished();
        server.getScheduler().performOneTick();

        assertTrue(Recorded.events(Action.CHAT).isEmpty());
    }

    @Test
    public void aCommandIsRecordedAsTypedAndARedactedOneByLabel() {
        command("/home set base");
        command("/login hunter2");

        assertEquals(new Payload.Text("/home set base"), Recorded.events(Action.COMMAND).get(0).payload()
                .orElseThrow());
        assertEquals(new Payload.Text("/login"), Recorded.events(Action.COMMAND).get(1).payload().orElseThrow());
    }

    @Test
    public void aSignCommandIsRecordedOnceAndMarkedAndRedactedLikeAnyCommand() {
        Block block = world.getBlockAt(7, 81, -6);
        block.setType(Material.OAK_SIGN);
        Sign sign = (Sign) block.getState();
        signCommand(sign, "/give Alex diamond 64");
        signCommand(sign, "login hunter2");

        assertEquals(List.of(new Payload.Text("[sign] /give Alex diamond 64"), new Payload.Text("[sign] /login")),
                texts(Recorded.events(Action.COMMAND)));
        assertEquals("Alex", Recorded.events(Action.COMMAND).getFirst().actor().name());
    }

    @Test
    public void aConsoleCommandIsRecordedByTheConsoleAtTheFirstWorldsSpawn() {
        consoleCommand(server.getConsoleSender(), "op Steve");
        consoleCommand(server.getConsoleSender(), "l hunter2");

        List<StoredEvent> events = Recorded.events(Action.COMMAND);
        assertEquals(List.of(new Payload.Text("/op Steve"), new Payload.Text("/l")), texts(events));
        StoredEvent event = events.getFirst();
        assertEquals("#console", event.actor().name());
        Location spawn = world.getSpawnLocation();
        assertEquals(spawn.getBlockX(), event.x());
        assertEquals(spawn.getBlockY(), event.y());
        assertEquals(spawn.getBlockZ(), event.z());
    }

    @Test
    public void onlyTheConsoleIsRecordedForServerCommands() {
        consoleCommand(player, "say from a command block");

        assertTrue(Recorded.events(Action.COMMAND).isEmpty());
    }

    @Test
    public void aRemoteConsoleCommandIsRecordedByRconAndRedacted() {
        remoteCommand(rcon(), "op Steve");
        remoteCommand(rcon(), "l hunter2");

        List<StoredEvent> events = Recorded.events(Action.COMMAND);
        assertEquals(List.of(new Payload.Text("/op Steve"), new Payload.Text("/l")), texts(events));
        StoredEvent event = events.getFirst();
        assertEquals("#rcon", event.actor().name());
        assertEquals(world.getSpawnLocation().getBlockX(), event.x());
        assertEquals(world.getSpawnLocation().getBlockZ(), event.z());
    }

    @Test
    public void onlyTheRemoteConsoleIsRecordedForRemoteCommands() {
        remoteCommand(player, "op Steve");

        assertTrue(Recorded.events(Action.COMMAND).isEmpty());
    }

    @Test
    public void consoleCommandsHaveTheirOwnSwitch() {
        TestPlugins.writeConfig(plugin, "logging:\n  console-commands: false\n");
        TestPlugins.reload();
        consoleCommand(server.getConsoleSender(), "op Steve");
        remoteCommand(rcon(), "op Steve");
        command("/spawn");

        assertEquals(List.of(new Payload.Text("/spawn")), texts(Recorded.events(Action.COMMAND)));
    }

    private void command(String line) {
        server.getPluginManager().callEvent(new PlayerCommandPreprocessEvent(player, line));
    }

    private void signCommand(Sign sign, String line) {
        server.getPluginManager().callEvent(new PlayerSignCommandPreprocessEvent(player, line, new HashSet<>(), sign,
                Side.FRONT));
    }

    private void consoleCommand(CommandSender sender, String line) {
        server.getPluginManager().callEvent(new ServerCommandEvent(sender, line));
    }

    private void remoteCommand(CommandSender sender, String line) {
        server.getPluginManager().callEvent(new RemoteServerCommandEvent(sender, line));
    }

    /** MockBukkit has no remote console sender; this one answers its server, name and address only. */
    private RemoteConsoleCommandSender rcon() {
        return (RemoteConsoleCommandSender) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {RemoteConsoleCommandSender.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getServer" -> server;
                    case "getName" -> "Rcon";
                    case "getAddress" -> new InetSocketAddress("127.0.0.1", 25575);
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "Rcon";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static List<Payload> texts(List<StoredEvent> events) {
        return events.stream().map(event -> event.payload().orElseThrow()).toList();
    }
}
