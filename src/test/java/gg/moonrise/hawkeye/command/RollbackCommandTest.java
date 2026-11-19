package gg.moonrise.hawkeye.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.core.service.capture.Actors;
import gg.moonrise.hawkeye.core.service.capture.EventRecorder;
import gg.moonrise.hawkeye.core.service.rollback.RollbackPreviews;
import gg.moonrise.hawkeye.core.storage.EventStore;
import gg.moonrise.hawkeye.core.storage.StorageState;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.entity.query.Area;
import gg.moonrise.hawkeye.entity.query.LookupQuery;
import gg.moonrise.hawkeye.entity.query.ShardScope;
import gg.moonrise.hawkeye.support.TestCommandManager;
import gg.moonrise.hawkeye.support.TestHawkeyePlugin;
import gg.moonrise.hawkeye.support.TestPlugins;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.command.ConsoleCommandSenderMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** Rollback, restore, preview, apply, cancel and undo from the command to the world and back. */
public class RollbackCommandTest {

    private static final String ROLLBACK = "hawkeye rollback u:alex t:1h r:10";

    private ServerMock server;
    private TestHawkeyePlugin plugin;
    private TestCommandManager commands;
    private World world;
    private RecordingPlayer alex;
    private EventRecorder recorder;
    private StorageState.Ready storage;

    @BeforeEach
    public void setUp() {
        server = MockBukkit.mock();
        plugin = TestPlugins.load();
        storage = TestPlugins.awaitStorage();
        world = server.addSimpleWorld("world");
        alex = new RecordingPlayer(server, "Alex");
        server.addPlayer(alex);
        alex.teleport(new Location(world, 0.5, 64, 0.5));
        alex.addAttachment(plugin, Permissions.ROLLBACK, true);
        commands = RollbackTestSupport.commands();
        recorder = TestPlugins.bean(EventRecorder.class);
        TestPlugins.drain(alex);
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void aRollbackPutsABrokenBlockBackAndFlagsTheEvent() throws Exception {
        Block block = broken(alex, 3, 64, 2, "minecraft:stone");
        TestPlugins.flush();

        commands.run(alex, ROLLBACK);
        List<String> replies = RollbackTestSupport.tickUntilReply(server, alex, "Finished the rollback");

        assertEquals(Material.STONE, block.getType());
        assertTrue(replies.contains("Starting the rollback of 1 event(s)..."), replies.toString());
        assertTrue(replies.stream().anyMatch(line -> line.startsWith("Finished the rollback: 1 block(s), 0 item "
                + "stack(s), 0 entit(ies) in ")), replies.toString());
        assertTrue(event(3, 64, 2).rolledBack());
    }

    @Test
    public void aRestoreRedoesARolledBackChange() throws Exception {
        Block block = broken(alex, 3, 64, 2, "minecraft:stone");
        TestPlugins.flush();
        commands.run(alex, ROLLBACK);
        RollbackTestSupport.tickUntilReply(server, alex, "Finished the rollback");

        commands.run(alex, "he rs u:alex t:1h r:10");
        RollbackTestSupport.tickUntilReply(server, alex, "Finished the restore");

        assertEquals(Material.AIR, block.getType());
        assertFalse(event(3, 64, 2).rolledBack());
    }

    @Test
    public void onlyTheNamedPlayersChangesAreRolledBack() throws Exception {
        PlayerMock steve = server.addPlayer("Steve");
        Block alexBlock = broken(alex, 1, 64, 1, "minecraft:stone");
        Block steveBlock = broken(steve, 2, 64, 1, "minecraft:dirt");
        TestPlugins.flush();

        commands.run(alex, "he rb u:alex t:1h r:10");
        RollbackTestSupport.tickUntilReply(server, alex, "Finished the rollback");

        assertEquals(Material.STONE, alexBlock.getType());
        assertEquals(Material.AIR, steveBlock.getType());
        assertFalse(event(2, 64, 1).rolledBack());
    }

    @Test
    public void aPreviewChangesNothingUntilItIsApplied() throws Exception {
        Block block = broken(alex, 3, 64, 2, "minecraft:stone");
        TestPlugins.flush();

        commands.run(alex, ROLLBACK + " #preview");
        List<String> replies = RollbackTestSupport.tickUntilReply(server, alex, "Previewing 1 block(s)");
        assertTrue(replies.stream().anyMatch(line -> line.contains("(expires in 5m)")), replies.toString());
        assertEquals(Material.AIR, block.getType());
        assertFalse(event(3, 64, 2).rolledBack());
        assertTrue(alex.sent(3, 64, 2, Material.STONE), alex.changes.toString());

        commands.run(alex, "hawkeye apply");
        RollbackTestSupport.tickUntilReply(server, alex, "Finished the rollback");
        assertEquals(Material.STONE, block.getType());
        assertTrue(event(3, 64, 2).rolledBack());

        commands.run(alex, "hawkeye apply");
        RollbackTestSupport.tickUntilReply(server, alex, "You have no preview to apply or cancel.");
    }

    @Test
    public void cancellingAPreviewSendsTheRealBlocksBack() throws Exception {
        Block block = broken(alex, 3, 64, 2, "minecraft:stone");
        TestPlugins.flush();
        commands.run(alex, ROLLBACK + " #preview");
        RollbackTestSupport.tickUntilReply(server, alex, "Previewing 1 block(s)");

        commands.run(alex, "hawkeye cancel");
        assertEquals(List.of("Preview cancelled."), TestPlugins.drain(alex));
        RollbackTestSupport.tickUntil(server, () -> alex.sent(3, 64, 2, Material.AIR), alex.changes::toString);

        assertEquals(Material.AIR, block.getType());
        assertFalse(event(3, 64, 2).rolledBack());
        commands.run(alex, "hawkeye cancel");
        assertEquals(List.of("You have no preview to apply or cancel."), TestPlugins.drain(alex));
    }

    @Test
    public void anExpiredPreviewIsGoneAndItsBlocksAreReverted() throws Exception {
        broken(alex, 3, 64, 2, "minecraft:stone");
        TestPlugins.flush();
        commands.run(alex, ROLLBACK + " #preview");
        RollbackTestSupport.tickUntilReply(server, alex, "Previewing 1 block(s)");

        assertEquals(1, TestPlugins.bean(RollbackPreviews.class).expireDue(Long.MAX_VALUE));
        RollbackTestSupport.tickUntilReply(server, alex, "Your rollback preview expired; the real blocks are back.");
        RollbackTestSupport.tickUntil(server, () -> alex.sent(3, 64, 2, Material.AIR), alex.changes::toString);

        commands.run(alex, "hawkeye apply");
        RollbackTestSupport.tickUntilReply(server, alex, "You have no preview to apply or cancel.");
    }

    @Test
    public void undoReversesTheLastOperationEachTime() throws Exception {
        Block block = broken(alex, 3, 64, 2, "minecraft:stone");
        TestPlugins.flush();
        commands.run(alex, "hawkeye undo");
        RollbackTestSupport.tickUntilReply(server, alex, "You have no rollback or restore to undo.");

        commands.run(alex, ROLLBACK);
        RollbackTestSupport.tickUntilReply(server, alex, "Finished the rollback");
        commands.run(alex, "hawkeye undo");
        RollbackTestSupport.tickUntilReply(server, alex, "Finished the restore");
        assertEquals(Material.AIR, block.getType());
        assertFalse(event(3, 64, 2).rolledBack());

        commands.run(alex, "hawkeye undo");
        RollbackTestSupport.tickUntilReply(server, alex, "Finished the rollback");
        assertEquals(Material.STONE, block.getType());
    }

    @Test
    public void anIssuerRunsOneOperationAtATime() {
        broken(alex, 3, 64, 2, "minecraft:stone");
        TestPlugins.flush();

        commands.run(alex, ROLLBACK);
        commands.run(alex, ROLLBACK);

        List<String> replies = RollbackTestSupport.tickUntilReply(server, alex, "Finished the rollback");
        assertTrue(replies.contains("Your previous rollback or restore is still running."), replies.toString());
    }

    @Test
    public void tooManyEventsAreRefused() {
        TestPlugins.writeConfig(plugin, "rollback:\n  max-events: 1\n");
        TestPlugins.reload();
        Block first = broken(alex, 3, 64, 2, "minecraft:stone");
        broken(alex, 4, 64, 2, "minecraft:stone");
        TestPlugins.flush();

        commands.run(alex, ROLLBACK);

        RollbackTestSupport.tickUntilReply(server, alex, "That would change 2 events (limit 1)");
        assertEquals(Material.AIR, first.getType());
    }

    @Test
    public void nothingMatchingIsReported() {
        commands.run(alex, "hawkeye rollback u:nobody t:1h r:10");
        RollbackTestSupport.tickUntilReply(server, alex, "Nothing to rollback.");
        commands.run(alex, "hawkeye restore u:alex t:1h r:10");
        RollbackTestSupport.tickUntilReply(server, alex, "Nothing to restore.");
    }

    @Test
    public void aTimeIsRequired() {
        commands.run(alex, "hawkeye rollback u:alex");
        assertEquals(List.of("Add a time, e.g. t:2h."), TestPlugins.drain(alex));
        commands.run(alex, "hawkeye rollback");
        assertEquals(List.of("Add a time, e.g. t:2h."), TestPlugins.drain(alex));
    }

    @Test
    public void theConsoleNamesAnArea() throws Exception {
        ConsoleCommandSenderMock console = server.getConsoleSender();
        Block block = broken(alex, 3, 64, 2, "minecraft:stone");
        TestPlugins.flush();

        commands.run(console, "hawkeye rollback u:alex t:1h");
        List<String> refused = TestPlugins.drain(console);
        assertEquals(1, refused.size(), refused.toString());
        assertTrue(refused.getFirst().startsWith("Give an area"), refused.getFirst());

        commands.run(console, "hawkeye rollback u:alex t:1h r:#world");
        RollbackTestSupport.tickUntilReply(server, console, "Finished the rollback");
        assertEquals(Material.STONE, block.getType());
    }

    @Test
    public void thisServersOwnNameIsLocal() throws Exception {
        alex.addAttachment(plugin, Permissions.NETWORK, true);
        Block block = broken(alex, 3, 64, 2, "minecraft:stone");
        TestPlugins.flush();

        commands.run(alex, "hawkeye rollback u:alex t:1h s:server");

        RollbackTestSupport.tickUntilReply(server, alex, "Finished the rollback");
        assertEquals(Material.STONE, block.getType());
    }

    @Test
    public void aNetworkRollbackRunsAsAJobAndReportsEachServer() throws Exception {
        alex.addAttachment(plugin, Permissions.NETWORK, true);
        Block block = broken(alex, 3, 64, 2, "minecraft:stone");
        TestPlugins.flush();

        commands.run(alex, "hawkeye rollback u:alex t:1h s:#network");

        List<String> replies = RollbackTestSupport.tickUntilReply(server, alex, "rollback done on server");
        assertEquals(Material.STONE, block.getType());
        assertTrue(replies.stream().anyMatch(line -> line.contains("Sent the rollback to server")), replies.toString());
        assertTrue(event(3, 64, 2).rolledBack());
    }

    @Test
    public void unknownServersAndNetworkPreviewsAreRefused() {
        alex.addAttachment(plugin, Permissions.NETWORK, true);
        commands.run(alex, "hawkeye rollback u:alex t:1h s:creative");
        RollbackTestSupport.tickUntilReply(server, alex, "No registered server matches");
        commands.run(alex, "hawkeye rollback u:alex t:1h s:#network #preview");
        RollbackTestSupport.tickUntilReply(server, alex, "#preview only works on this server");
    }

    @Test
    public void previewsAreForPlayers() {
        ConsoleCommandSenderMock console = server.getConsoleSender();
        commands.run(console, "hawkeye rollback t:1h r:#world #preview");
        RollbackTestSupport.tickUntilReply(server, console, "Only players can do that.");
        commands.run(console, "hawkeye apply");
        RollbackTestSupport.tickUntilReply(server, console, "Only players can do that.");
        commands.run(console, "hawkeye cancel");
        RollbackTestSupport.tickUntilReply(server, console, "Only players can do that.");
    }

    @Test
    public void withoutPermissionNothingRuns() {
        PlayerMock steve = server.addPlayer("Steve");
        broken(alex, 3, 64, 2, "minecraft:stone");
        TestPlugins.flush();
        Block block = world.getBlockAt(3, 64, 2);
        try {
            commands.run(steve, ROLLBACK);
        } catch (CompletionException refused) {
            // Cloud refuses the command for lack of permission.
        }
        server.getScheduler().performTicks(5);
        assertEquals(Material.AIR, block.getType());
        assertTrue(TestPlugins.drain(steve).stream().noneMatch(line -> line.contains("rollback")));
    }

    private @NotNull Block broken(@NotNull Player player, int x, int y, int z, @NotNull String state) {
        recorder.record(Action.BLOCK_BREAK, Actors.of(player), world, x, y, z, state, "minecraft:air", 0, null);
        Block block = world.getBlockAt(x, y, z);
        block.setType(Material.AIR);
        return block;
    }

    private @NotNull StoredEvent event(int x, int y, int z) throws Exception {
        List<StoredEvent> events = TestPlugins.bean(EventStore.class).find(storage.home(),
                LookupQuery.at(new Area.Block("world", x, y, z)), new ShardScope.All(), 0, 10, true);
        assertEquals(1, events.size(), events.toString());
        return events.getFirst();
    }

    /** A player that remembers the fake blocks it was sent. */
    private static final class RecordingPlayer extends PlayerMock {

        private final List<String> changes = new CopyOnWriteArrayList<>();

        private RecordingPlayer(@NotNull ServerMock server, @NotNull String name) {
            super(server, name);
        }

        @Override
        public void sendBlockChange(@NotNull Location location, @NotNull BlockData block) {
            changes.add(key(location.getBlockX(), location.getBlockY(), location.getBlockZ(), block.getMaterial()));
        }

        private boolean sent(int x, int y, int z, @NotNull Material material) {
            return changes.contains(key(x, y, z, material));
        }

        private static @NotNull String key(int x, int y, int z, @NotNull Material material) {
            return x + "," + y + "," + z + "=" + material;
        }
    }
}
