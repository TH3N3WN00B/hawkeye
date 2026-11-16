package gg.moonrise.hawkeye.listener.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.listener.Recorded;
import gg.moonrise.hawkeye.support.TestHawkeyePlugin;
import gg.moonrise.hawkeye.support.TestPlugins;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.Entity;
import org.bukkit.event.block.CauldronLevelChangeEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

public class CauldronListenerTest {

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
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void aPlayerFillingACauldronIsAChangeByThePlayer() {
        Block cauldron = world.getBlockAt(0, 64, 0);
        cauldron.setType(Material.CAULDRON);
        change(cauldron, player, CauldronLevelChangeEvent.ChangeReason.BUCKET_EMPTY, 3);

        StoredEvent event = Recorded.only(Action.BLOCK_CHANGE);
        assertEquals("Alex", event.actor().name());
        assertEquals("minecraft:cauldron", event.secondary());
        assertTrue(event.subject().startsWith("minecraft:water_cauldron"), event.subject());
    }

    @Test
    public void evaporationIsNatureAndFollowsTheNaturalSwitch() {
        Block cauldron = world.getBlockAt(0, 64, 0);
        cauldron.setBlockData(waterCauldron(2));
        change(cauldron, null, CauldronLevelChangeEvent.ChangeReason.EVAPORATE, 1);

        StoredEvent event = Recorded.only(Action.BLOCK_CHANGE);
        assertEquals("#nature", event.actor().name());
        assertEquals(waterCauldron(1).getAsString(), event.subject());

        TestPlugins.writeConfig(plugin, "logging:\n  natural: false\n");
        TestPlugins.reload();
        change(cauldron, null, CauldronLevelChangeEvent.ChangeReason.EVAPORATE, 1);
        assertEquals(1, Recorded.events(Action.BLOCK_CHANGE).size());
    }

    @Test
    public void anUnchangedCauldronIsNotRecorded() {
        Block cauldron = world.getBlockAt(0, 64, 0);
        cauldron.setBlockData(waterCauldron(3));
        change(cauldron, player, CauldronLevelChangeEvent.ChangeReason.BUCKET_EMPTY, 3);

        assertTrue(Recorded.events(Action.BLOCK_CHANGE).isEmpty());
    }

    private void change(Block cauldron, Entity entity, CauldronLevelChangeEvent.ChangeReason reason, int level) {
        BlockState state = cauldron.getState();
        state.setBlockData(level == 0 ? Material.CAULDRON.createBlockData() : waterCauldron(level));
        server.getPluginManager().callEvent(new CauldronLevelChangeEvent(cauldron, entity, reason, state));
    }

    private static Levelled waterCauldron(int level) {
        Levelled cauldron = (Levelled) Material.WATER_CAULDRON.createBlockData();
        cauldron.setLevel(level);
        return cauldron;
    }
}
