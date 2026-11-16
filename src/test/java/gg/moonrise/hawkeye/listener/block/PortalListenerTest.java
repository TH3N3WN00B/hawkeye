package gg.moonrise.hawkeye.listener.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.listener.Recorded;
import gg.moonrise.hawkeye.support.TestHawkeyePlugin;
import gg.moonrise.hawkeye.support.TestPlugins;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.event.world.PortalCreateEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

public class PortalListenerTest {

    private static final Action[] BLOCK_ACTIONS = {Action.BLOCK_BREAK, Action.BLOCK_PLACE, Action.BLOCK_CHANGE};

    private ServerMock server;
    private TestHawkeyePlugin plugin;
    private World world;

    @BeforeEach
    public void setUp() {
        server = MockBukkit.mock();
        plugin = TestPlugins.load();
        world = server.addSimpleWorld("world");
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void aPortalLitByAPlayerIsPlacedByThePlayerAndItsFrameIsSkipped() {
        PlayerMock player = server.addPlayer("Alex");
        Block frame = block(0, Material.OBSIDIAN);
        Block inside = block(1, Material.AIR);
        create(player, PortalCreateEvent.CreateReason.FIRE, state(frame, Material.OBSIDIAN),
                state(inside, Material.NETHER_PORTAL));

        StoredEvent event = Recorded.only(Action.BLOCK_PLACE);
        assertEquals("Alex", event.actor().name());
        assertTrue(event.subject().startsWith("minecraft:nether_portal"), event.subject());
        assertEquals(1, event.x());
        assertEquals(1, Recorded.events(BLOCK_ACTIONS).size(), "the unchanged frame is not recorded");
    }

    @Test
    public void aPortalWithoutAPlayerIsThePortalAndClearsWhatWasInTheWay() {
        Block stone = block(0, Material.STONE);
        Block air = block(1, Material.AIR);
        create(null, PortalCreateEvent.CreateReason.NETHER_PAIR, state(stone, Material.OBSIDIAN),
                state(air, Material.NETHER_PORTAL), state(block(2, Material.DIRT), Material.AIR));

        List<StoredEvent> events = Recorded.events(BLOCK_ACTIONS);
        assertEquals(3, events.size());
        assertTrue(events.stream().allMatch(event -> event.actor().name().equals("#portal")));
        assertEquals(Action.BLOCK_CHANGE, events.get(0).action());
        assertEquals("minecraft:obsidian", events.get(0).subject());
        assertEquals(Action.BLOCK_PLACE, events.get(1).action());
        assertEquals(Action.BLOCK_BREAK, events.get(2).action());
        assertEquals("minecraft:dirt", events.get(2).subject());
    }

    @Test
    public void aNaturalPortalFollowsTheNaturalSwitch() {
        TestPlugins.writeConfig(plugin, "logging:\n  natural: false\n");
        TestPlugins.reload();
        create(null, PortalCreateEvent.CreateReason.NETHER_PAIR, state(block(1, Material.AIR), Material.NETHER_PORTAL));

        assertTrue(Recorded.events(BLOCK_ACTIONS).isEmpty());
    }

    private void create(Entity entity, PortalCreateEvent.CreateReason reason, BlockState... states) {
        server.getPluginManager().callEvent(new PortalCreateEvent(List.of(states), world, entity, reason));
    }

    private static BlockState state(Block block, Material into) {
        BlockState state = block.getState();
        state.setType(into);
        return state;
    }

    private Block block(int x, Material material) {
        Block block = world.getBlockAt(x, 64, 0);
        block.setType(material);
        return block;
    }
}
