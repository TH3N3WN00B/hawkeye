package gg.moonrise.hawkeye.listener.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.core.service.capture.Actors;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.entity.payload.Payload;
import gg.moonrise.hawkeye.listener.Recorded;
import gg.moonrise.hawkeye.support.TestHawkeyePlugin;
import gg.moonrise.hawkeye.support.TestPlugins;
import io.papermc.paper.event.entity.EntityConstructEvent;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.Wither;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

public class EntityBuildListenerTest {

    private ServerMock server;
    private TestHawkeyePlugin plugin;
    private World world;
    private PlayerMock alex;

    @BeforeEach
    public void setUp() {
        server = MockBukkit.mock();
        plugin = TestPlugins.load();
        world = server.addSimpleWorld("world");
        alex = server.addPlayer("Alex");
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void aBuiltIronGolemIsBlamedOnThePlayerWhoPlacedThePumpkin() {
        List<Block> pattern = ironGolemPattern(10, 64, 10);
        placePumpkin(world.getBlockAt(11, 66, 10));
        IronGolem golem = world.spawn(new Location(world, 11.5, 64.05, 10.5), IronGolem.class);

        server.getPluginManager().callEvent(new EntityConstructEvent(golem, pattern));

        List<StoredEvent> broken = Recorded.events(Action.BLOCK_BREAK);
        assertEquals(5, broken.size(), broken.toString());
        assertTrue(broken.stream().allMatch(event -> event.actor().name().equals("Alex")), broken.toString());
        assertEquals(4, broken.stream().filter(event -> event.subject().equals("minecraft:iron_block")).count());
        StoredEvent spawned = Recorded.only(Action.ENTITY_SPAWN);
        assertEquals("Alex", spawned.actor().name());
        assertEquals("minecraft:iron_golem", spawned.subject());
        assertEquals(new Payload.EntityRef(golem.getUniqueId()), spawned.payload().orElseThrow());
        assertEquals(Actors.of(alex), Actors.taggedCause(golem).orElseThrow());
        assertEquals("Alex", Actors.of(golem).name());
    }

    @Test
    public void aPatternNobodyIsRememberedForIsBlamedOnTheEntityType() {
        Block skull = world.getBlockAt(21, 66, 20);
        skull.setType(Material.WITHER_SKELETON_SKULL);
        Block sand = world.getBlockAt(21, 65, 20);
        sand.setType(Material.SOUL_SAND);
        Wither wither = world.spawn(new Location(world, 21.5, 64.05, 20.5), Wither.class);

        server.getPluginManager().callEvent(new EntityConstructEvent(wither, List.of(skull, sand)));

        assertEquals("#wither", Recorded.only(Action.ENTITY_SPAWN).actor().name());
        assertEquals(2, Recorded.events(Action.BLOCK_BREAK).size());
        assertTrue(Actors.taggedCause(wither).isEmpty());
    }

    @Test
    public void aDisabledCategoryRecordsAndTagsNothing() {
        TestPlugins.writeConfig(plugin, "logging:\n  entities: false\n");
        TestPlugins.reload();
        List<Block> pattern = ironGolemPattern(10, 64, 10);
        placePumpkin(world.getBlockAt(11, 66, 10));
        IronGolem golem = world.spawn(new Location(world, 11.5, 64.05, 10.5), IronGolem.class);

        server.getPluginManager().callEvent(new EntityConstructEvent(golem, pattern));

        assertTrue(Recorded.events(Action.BLOCK_BREAK, Action.ENTITY_SPAWN).isEmpty());
        assertTrue(Actors.taggedCause(golem).isEmpty());
    }

    /** A T of iron blocks with the pumpkin's place empty, as the 3x3 pattern the server matches. */
    private List<Block> ironGolemPattern(int x, int y, int z) {
        List<Block> pattern = new ArrayList<>();
        for (int dx = 0; dx < 3; dx++) {
            for (int dy = 0; dy < 3; dy++) {
                Block block = world.getBlockAt(x + dx, y + dy, z);
                boolean iron = dy == 1 || dy == 0 && dx == 1;
                block.setType(iron ? Material.IRON_BLOCK : Material.AIR);
                pattern.add(block);
            }
        }
        return pattern;
    }

    private void placePumpkin(Block block) {
        BlockState replaced = block.getState();
        block.setType(Material.CARVED_PUMPKIN);
        server.getPluginManager().callEvent(new BlockPlaceEvent(block, replaced, block.getRelative(0, -1, 0),
                new ItemStack(Material.CARVED_PUMPKIN), alex, true, EquipmentSlot.HAND));
    }
}
