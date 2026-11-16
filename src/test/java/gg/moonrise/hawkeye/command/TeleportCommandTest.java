package gg.moonrise.hawkeye.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import gg.moonrise.hawkeye.support.TestCommandManager;
import gg.moonrise.hawkeye.support.TestCommandSource;
import gg.moonrise.hawkeye.support.TestPlugins;
import gg.moonrise.hawkeye.ui.lookup.AwaitReplies;
import io.papermc.paper.entity.TeleportFlag;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.incendo.cloud.suggestion.Suggestion;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

public class TeleportCommandTest {

    private ServerMock server;
    private TestCommandManager commands;
    private PlayerMock alex;

    @BeforeEach
    public void setUp() {
        server = MockBukkit.mock();
        TestPlugins.load();
        commands = TestPlugins.commands();
        World world = server.addSimpleWorld("world");
        server.addSimpleWorld("world_nether");
        alex = new TeleportingPlayer(server, "Alex");
        server.addPlayer(alex);
        alex.setOp(true);
        alex.setLocation(new Location(world, 0, 64, 0, 90, 10));
        TestPlugins.drain(alex);
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void tpMovesToTheCentreOfTheBlockKeepingTheView() {
        commands.run(alex, "hawkeye tp world_nether 10 70 -3");

        assertEquals(List.of("Teleported to 10 70 -3 in world_nether."), AwaitReplies.reply(server, alex));
        Location location = alex.getLocation();
        assertEquals("world_nether", location.getWorld().getName());
        assertEquals(10.5, location.getX());
        assertEquals(70.0, location.getY());
        assertEquals(-2.5, location.getZ());
        assertEquals(90, location.getYaw());
        assertEquals(10, location.getPitch());
    }

    @Test
    public void anUnknownWorldIsRefused() {
        commands.run(alex, "hawkeye tp nowhere 1 2 3");
        assertEquals(List.of("There is no world called nowhere on this server."), TestPlugins.drain(alex));
        assertEquals("world", alex.getLocation().getWorld().getName());
    }

    @Test
    public void tpIsForPlayers() {
        commands.run(server.getConsoleSender(), "hawkeye tp world 1 2 3");
        assertEquals(List.of("Only players can do that."), TestPlugins.drain(server.getConsoleSender()));
    }

    @Test
    public void worldNamesAreSuggested() {
        List<String> suggestions = commands.suggestionFactory()
                .suggestImmediately(new TestCommandSource(alex), "hawkeye tp neth").list().stream()
                .map(Suggestion::suggestion)
                .toList();
        assertEquals(List.of("world_nether"), suggestions);
    }

    /** MockBukkit 4.120 does not implement {@code teleportAsync}; on a server it completes after the move. */
    private static final class TeleportingPlayer extends PlayerMock {

        private TeleportingPlayer(@NotNull ServerMock server, @NotNull String name) {
            super(server, name);
        }

        @Override
        public @NotNull CompletableFuture<Boolean> teleportAsync(@NotNull Location location,
                @NotNull PlayerTeleportEvent.TeleportCause cause, @NotNull TeleportFlag @NotNull ... flags) {
            return CompletableFuture.completedFuture(teleport(location, cause));
        }
    }
}
