package gg.moonrise.hawkeye.listener.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.base.Function;
import gg.moonrise.hawkeye.core.service.capture.Actors;
import gg.moonrise.hawkeye.core.service.capture.CauseTracker;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.listener.Recorded;
import gg.moonrise.hawkeye.support.TestHawkeyePlugin;
import gg.moonrise.hawkeye.support.TestPlugins;
import java.util.EnumMap;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Axolotl;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.LeashHitch;
import org.bukkit.entity.Painting;
import org.bukkit.entity.Zombie;
import org.bukkit.entity.minecart.RideableMinecart;
import org.bukkit.event.Event;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityDamageEvent.DamageModifier;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingBreakEvent.RemoveCause;
import org.bukkit.event.player.PlayerBucketEntityEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Removed entities. MockBukkit cannot serialize entities, so the events carry no rollback payload here;
 * {@code RollbackPlannerTest} covers the payload's way back.
 */
public class EntityRemoveListenerTest {

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
    public void aFrameBrokenByAPlayerIsRemovedByThem() {
        ItemFrame frame = frame(2, 64, 2);

        call(new HangingBreakByEntityEvent(frame, alex, damage(DamageType.PLAYER_ATTACK)));

        StoredEvent event = Recorded.only(Action.ENTITY_REMOVE);
        assertEquals("Alex", event.actor().name());
        assertEquals("minecraft:item_frame", event.subject());
        assertEquals(2, event.x());
        assertEquals(64, event.y());
        assertEquals(2, event.z());
    }

    @Test
    public void aFrameShotOffItsWallIsBlamedOnTheShooter() {
        ItemFrame frame = frame(2, 64, 2);
        Arrow arrow = world.spawn(new Location(world, 2.5, 64.5, 1.5), Arrow.class);
        arrow.setShooter(alex);

        call(new HangingBreakByEntityEvent(frame, arrow, damage(DamageType.ARROW)));

        assertEquals("Alex", Recorded.only(Action.ENTITY_REMOVE).actor().name());
    }

    @Test
    public void aFrameThatLostItsWallIsBlamedOnWhoeverRemovedTheWall() {
        ItemFrame frame = frame(2, 64, 2);
        Block wall = frame.getLocation().getBlock().getRelative(frame.getAttachedFace());
        TestPlugins.bean(CauseTracker.class).rememberAround(wall, Actors.of(alex));

        call(new HangingBreakEvent(frame, RemoveCause.PHYSICS));

        assertEquals("Alex", Recorded.only(Action.ENTITY_REMOVE).actor().name());
    }

    @Test
    public void aFrameThatFellOffWithoutAKnownCauseIsPhysics() {
        call(new HangingBreakEvent(frame(2, 64, 2), RemoveCause.PHYSICS));

        assertEquals("#physics", Recorded.only(Action.ENTITY_REMOVE).actor().name());
    }

    @Test
    public void explosionsAndUncategorisedCausesAreNamed() {
        Painting painting = world.spawn(new Location(world, 4.5, 64.5, 4.97), Painting.class);
        call(new HangingBreakEvent(painting, RemoveCause.EXPLOSION));
        call(new HangingBreakEvent(frame(6, 64, 6), RemoveCause.DEFAULT));

        assertEquals("#explosion", Recorded.events(Action.ENTITY_REMOVE).get(0).actor().name());
        assertEquals("minecraft:painting", Recorded.events(Action.ENTITY_REMOVE).get(0).subject());
        assertEquals("#unknown", Recorded.events(Action.ENTITY_REMOVE).get(1).actor().name());
    }

    @Test
    public void aBrokenLeashKnotIsNotRecorded() {
        LeashHitch knot = world.spawn(new Location(world, 1.5, 64.5, 1.5), LeashHitch.class);

        call(new HangingBreakByEntityEvent(knot, alex, damage(DamageType.PLAYER_ATTACK)));

        assertTrue(Recorded.events(Action.ENTITY_REMOVE).isEmpty());
    }

    @Test
    public void aVehicleIsRemovedByItsAttackerElseByTheDamage() {
        RideableMinecart hit = world.spawn(new Location(world, 8.5, 64, 8.5), RideableMinecart.class);
        RideableMinecart burnt = world.spawn(new Location(world, 9.5, 64, 8.5), RideableMinecart.class);

        call(new VehicleDestroyEvent(hit, damage(DamageType.PLAYER_ATTACK), alex));
        call(new VehicleDestroyEvent(burnt, damage(DamageType.LAVA), null));

        assertEquals("Alex", Recorded.events(Action.ENTITY_REMOVE).get(0).actor().name());
        assertEquals("minecraft:minecart", Recorded.events(Action.ENTITY_REMOVE).get(0).subject());
        assertEquals("#lava", Recorded.events(Action.ENTITY_REMOVE).get(1).actor().name());
    }

    @Test
    public void aMobCaughtInABucketIsTakenByThePlayer() {
        Axolotl axolotl = world.spawn(new Location(world, 10.5, 64, 10.5), Axolotl.class);

        call(new PlayerBucketEntityEvent(alex, axolotl, new ItemStack(Material.WATER_BUCKET),
                new ItemStack(Material.AXOLOTL_BUCKET), EquipmentSlot.HAND));

        StoredEvent event = Recorded.only(Action.ENTITY_REMOVE);
        assertEquals("Alex", event.actor().name());
        assertEquals("minecraft:axolotl", event.subject());
    }

    @Test
    public void aHitEndCrystalIsRemovedByAndTaggedWithTheHitter() {
        EnderCrystal crystal = world.spawn(new Location(world, 12.5, 64, 12.5), EnderCrystal.class);
        Arrow arrow = world.spawn(new Location(world, 12.5, 65, 10.5), Arrow.class);
        arrow.setShooter(alex);

        hit(arrow, crystal, DamageCause.PROJECTILE, DamageType.ARROW);

        StoredEvent event = Recorded.only(Action.ENTITY_REMOVE);
        assertEquals("Alex", event.actor().name());
        assertEquals("minecraft:end_crystal", event.subject());
        assertEquals("Alex", Actors.of(crystal).name());
    }

    @Test
    public void otherEntitiesHitAreNotRecorded() {
        Zombie zombie = world.spawn(new Location(world, 12.5, 64, 12.5), Zombie.class);

        hit(alex, zombie, DamageCause.ENTITY_ATTACK, DamageType.PLAYER_ATTACK);

        assertTrue(Recorded.events(Action.ENTITY_REMOVE).isEmpty());
        assertTrue(Actors.taggedCause(zombie).isEmpty());
    }

    @Test
    public void aDisabledCategoryRecordsNothingButACrystalIsStillTaggedForItsExplosion() {
        TestPlugins.writeConfig(plugin, "logging:\n  entities: false\n");
        TestPlugins.reload();
        EnderCrystal crystal = world.spawn(new Location(world, 12.5, 64, 12.5), EnderCrystal.class);

        call(new HangingBreakByEntityEvent(frame(2, 64, 2), alex, damage(DamageType.PLAYER_ATTACK)));
        hit(alex, crystal, DamageCause.ENTITY_ATTACK, DamageType.PLAYER_ATTACK);

        assertTrue(Recorded.events(Action.ENTITY_REMOVE).isEmpty());
        assertEquals("Alex", Actors.of(crystal).name());
    }

    @Test
    public void withExplosionsOffAHitCrystalIsNotTagged() {
        TestPlugins.writeConfig(plugin, "logging:\n  explosions: false\n");
        TestPlugins.reload();
        EnderCrystal crystal = world.spawn(new Location(world, 12.5, 64, 12.5), EnderCrystal.class);

        hit(alex, crystal, DamageCause.ENTITY_ATTACK, DamageType.PLAYER_ATTACK);

        assertEquals("Alex", Recorded.only(Action.ENTITY_REMOVE).actor().name());
        assertTrue(Actors.taggedCause(crystal).isEmpty());
    }

    /** A frame in the block at x, y, z, hanging on the wall to its south. */
    private ItemFrame frame(int x, int y, int z) {
        ItemFrame frame = world.spawn(new Location(world, x + 0.5, y + 0.5, z + 0.97), ItemFrame.class);
        frame.setFacingDirection(BlockFace.NORTH, true);
        return frame;
    }

    /** Paper has no damage event constructor without the deprecated damage modifiers. */
    @SuppressWarnings("deprecation")
    private void hit(Entity damager, Entity victim, DamageCause cause, DamageType type) {
        Map<DamageModifier, Double> modifiers = new EnumMap<>(DamageModifier.class);
        modifiers.put(DamageModifier.BASE, 1.0);
        Map<DamageModifier, Function<? super Double, Double>> functions = new EnumMap<>(DamageModifier.class);
        functions.put(DamageModifier.BASE, damage -> 0.0);
        call(new EntityDamageByEntityEvent(damager, victim, cause, damage(type), modifiers, functions, false));
    }

    private static DamageSource damage(DamageType type) {
        return DamageSource.builder(type).build();
    }

    private void call(Event event) {
        server.getPluginManager().callEvent(event);
    }
}
