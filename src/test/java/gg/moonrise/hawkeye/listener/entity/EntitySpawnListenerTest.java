package gg.moonrise.hawkeye.listener.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Axolotl;
import org.bukkit.entity.Cow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.LeashHitch;
import org.bukkit.entity.Zombie;
import org.bukkit.entity.minecart.RideableMinecart;
import org.bukkit.event.Event;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

public class EntitySpawnListenerTest {

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
    public void aHungItemFrameIsASpawnWithItsUuid() {
        ItemFrame frame = world.spawn(new Location(world, 2.5, 64.5, 2.97), ItemFrame.class);

        call(new HangingPlaceEvent(frame, alex, world.getBlockAt(2, 64, 3), BlockFace.NORTH, EquipmentSlot.HAND,
                new ItemStack(Material.ITEM_FRAME)));

        StoredEvent event = Recorded.only(Action.ENTITY_SPAWN);
        assertEquals("Alex", event.actor().name());
        assertEquals("minecraft:item_frame", event.subject());
        assertEquals(new Payload.EntityRef(frame.getUniqueId()), event.payload().orElseThrow());
        assertEquals(2, event.x());
        assertEquals(64, event.y());
        assertEquals(2, event.z());
    }

    @Test
    public void aPlacedArmourStandIsASpawn() {
        ArmorStand stand = world.spawn(new Location(world, 4.5, 64, 4.5), ArmorStand.class);

        call(new EntityPlaceEvent(stand, alex, world.getBlockAt(4, 63, 4), BlockFace.UP, EquipmentSlot.HAND));

        StoredEvent event = Recorded.only(Action.ENTITY_SPAWN);
        assertEquals("Alex", event.actor().name());
        assertEquals("minecraft:armor_stand", event.subject());
        assertEquals(new Payload.EntityRef(stand.getUniqueId()), event.payload().orElseThrow());
    }

    @Test
    public void leashKnotsAndPlacementsWithoutAPlayerAreNotRecorded() {
        LeashHitch knot = world.spawn(new Location(world, 1.5, 64.5, 1.5), LeashHitch.class);
        call(new HangingPlaceEvent(knot, alex, world.getBlockAt(1, 64, 1), BlockFace.SELF, EquipmentSlot.HAND,
                new ItemStack(Material.LEAD)));
        RideableMinecart minecart = world.spawn(new Location(world, 3.5, 64, 3.5), RideableMinecart.class);
        call(new EntityPlaceEvent(minecart, null, world.getBlockAt(3, 63, 3), BlockFace.UP, EquipmentSlot.HAND));

        assertTrue(Recorded.events(Action.ENTITY_SPAWN).isEmpty());
    }

    @Test
    public void aMobFromASpawnEggIsBlamedOnThePlayerWhoUsedIt() {
        Block ground = world.getBlockAt(6, 63, 6);
        ground.setType(Material.STONE);
        call(new PlayerInteractEvent(alex, org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK,
                new ItemStack(Material.ZOMBIE_SPAWN_EGG), ground, BlockFace.UP, EquipmentSlot.HAND));

        Zombie zombie = spawn(new Location(world, 6.5, 64, 6.5), Zombie.class, SpawnReason.SPAWNER_EGG);

        StoredEvent event = Recorded.only(Action.ENTITY_SPAWN);
        assertEquals("Alex", event.actor().name());
        assertEquals("minecraft:zombie", event.subject());
        assertEquals(new Payload.EntityRef(zombie.getUniqueId()), event.payload().orElseThrow());
    }

    @Test
    public void aSpawnEggUsedOnAMobBlamesThePlayerForTheBaby() {
        Cow parent = world.spawn(new Location(world, 8.5, 64, 8.5), Cow.class);
        alex.getInventory().setItemInMainHand(new ItemStack(Material.COW_SPAWN_EGG));
        call(new PlayerInteractEntityEvent(alex, parent, EquipmentSlot.HAND));

        spawn(new Location(world, 8.5, 64, 8.5), Cow.class, SpawnReason.SPAWNER_EGG);

        assertEquals("Alex", Recorded.only(Action.ENTITY_SPAWN).actor().name());
    }

    @Test
    public void aSpawnEggWithoutAKnownUserIsTheSpawnEggSource() {
        spawn(new Location(world, 20.5, 64, 20.5), Zombie.class, SpawnReason.SPAWNER_EGG);

        assertEquals("#spawn_egg", Recorded.only(Action.ENTITY_SPAWN).actor().name());
    }

    @Test
    public void aDispensedEggIsTheDispensers() {
        spawn(new Location(world, 20.5, 64, 20.5), Zombie.class, SpawnReason.DISPENSE_EGG);

        assertEquals("#dispenser", Recorded.only(Action.ENTITY_SPAWN).actor().name());
    }

    @Test
    public void aMobReleasedFromABucketIsBlamedOnThePlayerWhoEmptiedIt() {
        Block water = world.getBlockAt(10, 64, 10);
        call(new PlayerBucketEmptyEvent(alex, water, world.getBlockAt(10, 63, 10), BlockFace.UP,
                Material.AXOLOTL_BUCKET, new ItemStack(Material.AXOLOTL_BUCKET), EquipmentSlot.HAND));

        spawn(new Location(world, 10.5, 64, 10.5), Axolotl.class, SpawnReason.BUCKET);

        StoredEvent event = Recorded.only(Action.ENTITY_SPAWN);
        assertEquals("Alex", event.actor().name());
        assertEquals("minecraft:axolotl", event.subject());
    }

    @Test
    public void aWaterBucketRemembersNobodyForAMob() {
        Block water = world.getBlockAt(12, 64, 12);
        call(new PlayerBucketEmptyEvent(alex, water, world.getBlockAt(12, 63, 12), BlockFace.UP,
                Material.WATER_BUCKET, new ItemStack(Material.WATER_BUCKET), EquipmentSlot.HAND));

        spawn(new Location(world, 12.5, 64, 12.5), Axolotl.class, SpawnReason.BUCKET);

        assertEquals("#dispenser", Recorded.only(Action.ENTITY_SPAWN).actor().name());
    }

    @Test
    public void naturalSpawnsAreNotRecorded() {
        spawn(new Location(world, 14.5, 64, 14.5), Zombie.class, SpawnReason.NATURAL);
        spawn(new Location(world, 14.5, 64, 14.5), Zombie.class, SpawnReason.SPAWNER);

        assertTrue(Recorded.events(Action.ENTITY_SPAWN).isEmpty());
    }

    @Test
    public void aDisabledCategoryRecordsNothing() {
        TestPlugins.writeConfig(plugin, "logging:\n  entities: false\n");
        TestPlugins.reload();
        ArmorStand stand = world.spawn(new Location(world, 4.5, 64, 4.5), ArmorStand.class);
        call(new EntityPlaceEvent(stand, alex, world.getBlockAt(4, 63, 4), BlockFace.UP, EquipmentSlot.HAND));
        spawn(new Location(world, 20.5, 64, 20.5), Zombie.class, SpawnReason.DISPENSE_EGG);

        assertTrue(Recorded.events(Action.ENTITY_SPAWN).isEmpty());
    }

    private <T extends Entity> T spawn(Location location, Class<T> type, SpawnReason reason) {
        return world.spawn(location, type, entity -> {
        }, reason);
    }

    private void call(Event event) {
        server.getPluginManager().callEvent(event);
    }
}
