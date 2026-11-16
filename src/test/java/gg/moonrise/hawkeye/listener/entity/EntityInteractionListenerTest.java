package gg.moonrise.hawkeye.listener.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.entity.payload.Payload;
import gg.moonrise.hawkeye.listener.Recorded;
import gg.moonrise.hawkeye.support.TestHawkeyePlugin;
import gg.moonrise.hawkeye.support.TestPlugins;
import io.papermc.paper.event.entity.EntityDyeEvent;
import io.papermc.paper.event.player.PlayerNameEntityEvent;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Sheep;
import org.bukkit.entity.Wolf;
import org.bukkit.event.Event;
import org.bukkit.event.entity.EntityTameEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.event.player.PlayerUnleashEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

public class EntityInteractionListenerTest {

    private ServerMock server;
    private TestHawkeyePlugin plugin;
    private PlayerMock alex;
    private Sheep sheep;
    private Wolf wolf;

    @BeforeEach
    public void setUp() {
        server = MockBukkit.mock();
        plugin = TestPlugins.load();
        World world = server.addSimpleWorld("world");
        alex = server.addPlayer("Alex");
        sheep = world.spawn(new Location(world, 2.5, 64, 2.5), Sheep.class);
        wolf = world.spawn(new Location(world, 5.5, 64, 5.5), Wolf.class);
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void shearingIsRecordedAtTheEntity() {
        shear();

        StoredEvent event = Recorded.only(Action.ENTITY_SHEAR);
        assertEquals("Alex", event.actor().name());
        assertEquals("minecraft:sheep", event.subject());
        assertEquals(2, event.x());
    }

    @Test
    public void namingKeepsTheNewNameAsPlainText() {
        Component name = Component.text("Dinner").append(Component.text("bone", NamedTextColor.RED));

        call(new PlayerNameEntityEvent(alex, sheep, name, true));

        StoredEvent event = Recorded.only(Action.ENTITY_NAME);
        assertEquals(new Payload.Text("Dinnerbone"), event.payload().orElseThrow());
    }

    @Test
    public void leashingAndUnleashingAreThePlayers() {
        call(new PlayerLeashEntityEvent(wolf, alex, alex, EquipmentSlot.HAND));
        call(new PlayerUnleashEntityEvent(wolf, alex, EquipmentSlot.HAND, true));

        assertEquals("Alex", Recorded.only(Action.ENTITY_LEASH).actor().name());
        assertEquals("minecraft:wolf", Recorded.only(Action.ENTITY_UNLEASH).subject());
    }

    @Test
    public void tamingIsTheNewOwners() {
        call(new EntityTameEvent(wolf, alex));

        StoredEvent event = Recorded.only(Action.ENTITY_TAME);
        assertEquals("Alex", event.actor().name());
        assertEquals("minecraft:wolf", event.subject());
    }

    @Test
    public void dyeingKeepsTheColourAndNeedsAPlayer() {
        call(new EntityDyeEvent(sheep, DyeColor.LIGHT_BLUE, alex));
        call(new EntityDyeEvent(sheep, DyeColor.RED, null));

        StoredEvent event = Recorded.only(Action.ENTITY_DYE);
        assertEquals("Alex", event.actor().name());
        assertEquals(new Payload.Text("light_blue"), event.payload().orElseThrow());
    }

    @Test
    public void aDisabledCategoryRecordsNothing() {
        TestPlugins.writeConfig(plugin, "logging:\n  entity-interactions: false\n");
        TestPlugins.reload();

        shear();
        call(new EntityTameEvent(wolf, alex));
        call(new EntityDyeEvent(sheep, DyeColor.RED, alex));

        assertTrue(Recorded.events(Action.ENTITY_SHEAR, Action.ENTITY_TAME, Action.ENTITY_DYE).isEmpty());
    }

    private void shear() {
        call(new PlayerShearEntityEvent(alex, sheep, new ItemStack(Material.SHEARS), EquipmentSlot.HAND,
                List.of(new ItemStack(Material.WHITE_WOOL))));
    }

    private void call(Event event) {
        server.getPluginManager().callEvent(event);
    }
}
