package gg.moonrise.hawkeye.listener.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.core.service.capture.Actors;
import gg.moonrise.hawkeye.core.service.capture.CauseTracker;
import gg.moonrise.hawkeye.core.service.capture.EventRecorder;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.entity.payload.Payload;
import gg.moonrise.hawkeye.listener.Recorded;
import gg.moonrise.hawkeye.support.TestHawkeyePlugin;
import gg.moonrise.hawkeye.support.TestPlugins;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.Wither;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * The fallback for servers before Paper 26.2. MockBukkit has the construct event, so the plugin registers
 * {@code EntityConstructListener} instead; the fallback is built here and called directly, while
 * {@link EntityBuildListener} (registered on every server) remembers who placed the pattern blocks.
 */
public class BuiltEntitySpawnListenerTest {

    private ServerMock server;
    private TestHawkeyePlugin plugin;
    private World world;
    private PlayerMock alex;
    private BuiltEntitySpawnListener listener;

    @BeforeEach
    public void setUp() {
        server = MockBukkit.mock();
        plugin = TestPlugins.load();
        world = server.addSimpleWorld("world");
        alex = server.addPlayer("Alex");
        listener = new BuiltEntitySpawnListener(TestPlugins.bean(EventRecorder.class),
                TestPlugins.bean(CauseTracker.class));
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void aBuiltIronGolemIsSpawnedByThePlayerWhoPlacedItsHead() {
        placePumpkin(world.getBlockAt(11, 66, 10));
        IronGolem golem = world.spawn(new Location(world, 11.5, 64.05, 10.5), IronGolem.class);

        listener.onSpawn(new CreatureSpawnEvent(golem, SpawnReason.BUILD_IRONGOLEM));

        StoredEvent spawned = Recorded.only(Action.ENTITY_SPAWN);
        assertEquals("Alex", spawned.actor().name());
        assertEquals("minecraft:iron_golem", spawned.subject());
        assertEquals(new Payload.EntityRef(golem.getUniqueId()), spawned.payload().orElseThrow());
        assertEquals(Actors.of(alex), Actors.taggedCause(golem).orElseThrow());
        assertTrue(Recorded.events(Action.BLOCK_BREAK).isEmpty(), "the spawn event names no pattern blocks");
    }

    @Test
    public void aBuiltEntityNobodyIsRememberedForIsSpawnedByItsType() {
        Wither wither = world.spawn(new Location(world, 21.5, 64.55, 20.5), Wither.class);

        listener.onSpawn(new CreatureSpawnEvent(wither, SpawnReason.BUILD_WITHER));

        assertEquals("#wither", Recorded.only(Action.ENTITY_SPAWN).actor().name());
        assertTrue(Actors.taggedCause(wither).isEmpty());
    }

    @Test
    public void aBuilderFurtherAwayThanThePatternOrAnotherSpawnReasonIsNotUsed() {
        placePumpkin(world.getBlockAt(11, 68, 10));
        IronGolem golem = world.spawn(new Location(world, 11.5, 64.05, 10.5), IronGolem.class);
        IronGolem natural = world.spawn(new Location(world, 30.5, 64.05, 30.5), IronGolem.class);

        listener.onSpawn(new CreatureSpawnEvent(golem, SpawnReason.BUILD_IRONGOLEM));
        listener.onSpawn(new CreatureSpawnEvent(natural, SpawnReason.VILLAGE_DEFENSE));

        assertEquals("#iron_golem", Recorded.only(Action.ENTITY_SPAWN).actor().name());
    }

    @Test
    public void aDisabledCategoryRecordsAndTagsNothing() {
        TestPlugins.writeConfig(plugin, "logging:\n  entities: false\n");
        TestPlugins.reload();
        placePumpkin(world.getBlockAt(11, 66, 10));
        IronGolem golem = world.spawn(new Location(world, 11.5, 64.05, 10.5), IronGolem.class);

        listener.onSpawn(new CreatureSpawnEvent(golem, SpawnReason.BUILD_IRONGOLEM));

        assertTrue(Recorded.events(Action.ENTITY_SPAWN).isEmpty());
        assertTrue(Actors.taggedCause(golem).isEmpty());
    }

    private void placePumpkin(Block block) {
        BlockState replaced = block.getState();
        block.setType(Material.CARVED_PUMPKIN);
        server.getPluginManager().callEvent(new BlockPlaceEvent(block, replaced, block.getRelative(0, -1, 0),
                new ItemStack(Material.CARVED_PUMPKIN), alex, true, EquipmentSlot.HAND));
    }
}
