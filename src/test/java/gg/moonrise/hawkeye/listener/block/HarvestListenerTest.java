package gg.moonrise.hawkeye.listener.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.listener.Recorded;
import gg.moonrise.hawkeye.support.TestHawkeyePlugin;
import gg.moonrise.hawkeye.support.TestPlugins;
import io.papermc.paper.event.block.PlayerShearBlockEvent;
import io.papermc.paper.event.player.PlayerFlowerPotManipulateEvent;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.event.player.PlayerHarvestBlockEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

public class HarvestListenerTest {

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
    public void pickedSweetBerriesAreAChangeByThePlayerNextTick() {
        Block bush = world.getBlockAt(0, 64, 0);
        bush.setBlockData(berries(3));
        server.getPluginManager().callEvent(new PlayerHarvestBlockEvent(player, bush, EquipmentSlot.HAND,
                List.of(new ItemStack(Material.SWEET_BERRIES, 2))));
        assertTrue(Recorded.events(Action.BLOCK_CHANGE).isEmpty(), "nothing is known before the tick");
        bush.setBlockData(berries(1));
        server.getScheduler().performOneTick();

        StoredEvent event = Recorded.only(Action.BLOCK_CHANGE);
        assertEquals("Alex", event.actor().name());
        assertEquals(berries(1).getAsString(), event.subject());
        assertEquals(berries(3).getAsString(), event.secondary());
    }

    @Test
    public void caveVinesAndAnUnchangedBlockAreNotRecorded() {
        Block vines = world.getBlockAt(0, 64, 0);
        vines.setType(Material.CAVE_VINES);
        server.getPluginManager().callEvent(new PlayerHarvestBlockEvent(player, vines, EquipmentSlot.HAND, List.of()));
        vines.setType(Material.CAVE_VINES_PLANT);
        Block bush = world.getBlockAt(1, 64, 0);
        bush.setBlockData(berries(1));
        server.getPluginManager().callEvent(new PlayerHarvestBlockEvent(player, bush, EquipmentSlot.HAND, List.of()));
        server.getScheduler().performOneTick();

        assertTrue(Recorded.events(Action.BLOCK_CHANGE).isEmpty());
    }

    @Test
    public void aShearedPumpkinIsAChange() {
        Block pumpkin = world.getBlockAt(0, 64, 0);
        pumpkin.setType(Material.PUMPKIN);
        server.getPluginManager().callEvent(new PlayerShearBlockEvent(player, pumpkin, new ItemStack(Material.SHEARS),
                EquipmentSlot.HAND, List.of(new ItemStack(Material.PUMPKIN_SEEDS, 4))));
        pumpkin.setType(Material.CARVED_PUMPKIN);
        server.getScheduler().performOneTick();

        StoredEvent event = Recorded.only(Action.BLOCK_CHANGE);
        assertEquals("Alex", event.actor().name());
        assertEquals("minecraft:pumpkin", event.secondary());
        assertTrue(event.subject().startsWith("minecraft:carved_pumpkin"), event.subject());
    }

    @Test
    public void aFlowerPotFollowsTheBlockPlaceSwitch() {
        Block pot = world.getBlockAt(0, 64, 0);
        pot.setType(Material.FLOWER_POT);
        pot(pot, true);
        pot.setType(Material.POTTED_POPPY);
        server.getScheduler().performOneTick();

        StoredEvent event = Recorded.only(Action.BLOCK_CHANGE);
        assertEquals("minecraft:potted_poppy", event.subject());
        assertEquals("minecraft:flower_pot", event.secondary());

        TestPlugins.writeConfig(plugin, "logging:\n  block-place: false\n");
        TestPlugins.reload();
        pot(pot, false);
        pot.setType(Material.FLOWER_POT);
        server.getScheduler().performOneTick();
        assertEquals(1, Recorded.events(Action.BLOCK_CHANGE).size());
    }

    private void pot(Block pot, boolean placing) {
        server.getPluginManager().callEvent(new PlayerFlowerPotManipulateEvent(player, pot,
                new ItemStack(Material.POPPY), placing));
    }

    private static Ageable berries(int age) {
        Ageable bush = (Ageable) Material.SWEET_BERRY_BUSH.createBlockData();
        bush.setAge(age);
        return bush;
    }
}
