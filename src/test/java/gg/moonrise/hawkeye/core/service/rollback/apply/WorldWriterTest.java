package gg.moonrise.hawkeye.core.service.rollback.apply;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.core.service.rollback.plan.PlannedChange;
import gg.moonrise.hawkeye.core.service.rollback.plan.SignPatch;
import gg.moonrise.hawkeye.entity.payload.SignSides;
import gg.moonrise.hawkeye.support.TestPlugins;
import gg.moonrise.hawkeye.util.position.BlockPositions;
import io.papermc.paper.block.TileStateInventoryHolder;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.Jukebox;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Zombie;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** The world side of a planned change, on MockBukkit blocks and entities. */
public class WorldWriterTest {

    private ServerMock server;
    private World world;
    private WorldWriter writer;

    @BeforeEach
    public void setUp() {
        server = MockBukkit.mock();
        TestPlugins.load();
        world = server.addSimpleWorld("world");
        writer = TestPlugins.bean(WorldWriter.class);
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void aBlockStateIsSet() {
        Block block = world.getBlockAt(1, 64, 1);
        assertEquals(WorldWriter.Effect.BLOCK, writer.apply(world, new PlannedChange.SetBlock(position(1, 64, 1),
                "minecraft:stone", SignPatch.NONE, List.of())));
        assertEquals(Material.STONE, block.getType());
    }

    @Test
    public void anUnknownBlockStateChangesNothing() {
        Block block = world.getBlockAt(1, 64, 1);
        block.setType(Material.DIRT);
        assertEquals(WorldWriter.Effect.NONE, writer.apply(world, new PlannedChange.SetBlock(position(1, 64, 1),
                "minecraft:not_a_block", SignPatch.NONE, List.of())));
        assertEquals(Material.DIRT, block.getType());
    }

    @Test
    public void signTextIsWrittenOnTheFlaggedSidesOnly() {
        Block block = world.getBlockAt(2, 64, 2);
        block.setType(Material.OAK_SIGN);
        Sign sign = (Sign) block.getState();
        sign.getSide(Side.FRONT).line(3, Component.text("gone"));
        sign.getSide(Side.BACK).line(0, Component.text("kept"));
        sign.update(true, false);

        SignPatch patch = new SignPatch(new SignSides(List.of("{\"text\":\"hello\"}", "plain"), List.of()), true,
                false);
        assertEquals(WorldWriter.Effect.BLOCK, writer.apply(world, new PlannedChange.SetSignText(position(2, 64, 2),
                patch)));

        Sign written = (Sign) block.getState();
        assertEquals(Component.text("hello"), written.getSide(Side.FRONT).line(0));
        assertEquals(Component.text("plain"), written.getSide(Side.FRONT).line(1));
        assertEquals(Component.empty(), written.getSide(Side.FRONT).line(3));
        assertEquals(Component.text("kept"), written.getSide(Side.BACK).line(0));
    }

    @Test
    public void signTextNeedsASign() {
        assertEquals(WorldWriter.Effect.NONE, writer.apply(world, new PlannedChange.SetSignText(position(2, 64, 2),
                SignPatch.full(SignSides.EMPTY))));
    }

    @Test
    public void containerItemsAreTakenOutAndPutBack() {
        Block block = world.getBlockAt(3, 64, 3);
        block.setType(Material.CHEST);
        Container chest = (Container) block.getState();
        chest.getInventory().addItem(new ItemStack(Material.DIAMOND, 10));

        assertEquals(WorldWriter.Effect.ITEMS, writer.apply(world, new PlannedChange.AdjustItems(position(3, 64, 3),
                "minecraft:diamond", new byte[0], -4)));
        assertEquals(6, count(block, Material.DIAMOND));
        assertEquals(WorldWriter.Effect.ITEMS, writer.apply(world, new PlannedChange.AdjustItems(position(3, 64, 3),
                "minecraft:diamond", new byte[0], 70)));
        assertEquals(76, count(block, Material.DIAMOND));
        assertEquals(WorldWriter.Effect.NONE, writer.apply(world, new PlannedChange.AdjustItems(position(4, 64, 3),
                "minecraft:diamond", new byte[0], 1)));
    }

    @Test
    public void blocksThatHoldItemsWithoutBeingContainersTakeThemBackAndGiveThemUp() {
        Block bookshelf = block(11, Material.CHISELED_BOOKSHELF);
        Block shelf = block(12, Material.OAK_SHELF);
        Block lectern = block(13, Material.LECTERN);
        Block pot = block(14, Material.DECORATED_POT);

        assertEquals(WorldWriter.Effect.ITEMS, adjust(11, 64, 0, "minecraft:enchanted_book", 2));
        assertEquals(WorldWriter.Effect.ITEMS, adjust(12, 64, 0, "minecraft:diamond", 3));
        assertEquals(WorldWriter.Effect.ITEMS, adjust(13, 64, 0, "minecraft:writable_book", 1));
        assertEquals(WorldWriter.Effect.ITEMS, adjust(14, 64, 0, "minecraft:emerald", 5));
        assertEquals(2, held(bookshelf, Material.ENCHANTED_BOOK));
        assertEquals(3, held(shelf, Material.DIAMOND));
        assertEquals(1, held(lectern, Material.WRITABLE_BOOK));
        assertEquals(5, held(pot, Material.EMERALD));

        assertEquals(WorldWriter.Effect.ITEMS, adjust(11, 64, 0, "minecraft:enchanted_book", -1));
        assertEquals(WorldWriter.Effect.ITEMS, adjust(12, 64, 0, "minecraft:diamond", -3));
        assertEquals(1, held(bookshelf, Material.ENCHANTED_BOOK));
        assertEquals(0, held(shelf, Material.DIAMOND));
        assertEquals(WorldWriter.Effect.NONE, adjust(12, 64, 0, "minecraft:diamond", -1));
    }

    @Test
    public void aJukeboxGetsItsRecordBackAndGivesUpTheSameOne() {
        Block block = block(15, Material.JUKEBOX);

        assertEquals(WorldWriter.Effect.ITEMS, adjust(15, 64, 0, "minecraft:music_disc_cat", 1));
        assertEquals(Material.MUSIC_DISC_CAT, ((Jukebox) block.getState()).getRecord().getType());
        assertEquals(WorldWriter.Effect.NONE, adjust(15, 64, 0, "minecraft:music_disc_13", 1));
        assertEquals(WorldWriter.Effect.NONE, adjust(15, 64, 0, "minecraft:music_disc_13", -1));

        assertEquals(WorldWriter.Effect.ITEMS, adjust(15, 64, 0, "minecraft:music_disc_cat", -1));
        assertTrue(((Jukebox) block.getState()).getRecord().isEmpty());
    }

    @Test
    public void aSpawnedEntityIsRemovedByItsUuid() {
        Zombie zombie = world.spawn(new Location(world, 30.5, 64, 30.5), Zombie.class);

        assertEquals(WorldWriter.Effect.ENTITY, writer.apply(world, new PlannedChange.RemoveEntity(position(1, 64, 1),
                zombie.getUniqueId())));

        assertFalse(zombie.isValid());
        assertNull(world.getEntity(zombie.getUniqueId()));
        assertEquals(WorldWriter.Effect.NONE, writer.apply(world, new PlannedChange.RemoveEntity(position(1, 64, 1),
                zombie.getUniqueId())));
    }

    @Test
    public void playersAreNeverRemoved() {
        PlayerMock player = server.addPlayer("Alex");
        player.teleport(new Location(world, 1, 64, 1));

        assertEquals(WorldWriter.Effect.NONE, writer.apply(world, new PlannedChange.RemoveEntity(position(1, 64, 1),
                player.getUniqueId())));
        assertTrue(player.isValid());
    }

    @Test
    public void anEntityOfAnotherRegionIsLeftAlone() throws Exception {
        Zombie zombie = world.spawn(new Location(world, 1.5, 64, 1.5), Zombie.class);
        AtomicReference<WorldWriter.Effect> effect = new AtomicReference<>();

        // MockBukkit says a thread owns an entity's region only when it is the main thread.
        Thread other = new Thread(() -> effect.set(writer.apply(world, new PlannedChange.RemoveEntity(
                position(1, 64, 1), zombie.getUniqueId()))));
        other.start();
        other.join(TimeUnit.SECONDS.toMillis(10));

        assertEquals(WorldWriter.Effect.NONE, effect.get());
        assertTrue(zombie.isValid());
    }

    @Test
    public void itemsGoIntoAnEmptyItemFrameAndOutOfOneHoldingThem() {
        ItemFrame frame = world.spawn(new Location(world, 5.5, 64.5, 5.97), ItemFrame.class);

        assertEquals(WorldWriter.Effect.ITEMS, adjust(5, 64, 5, "minecraft:diamond", 1));
        assertEquals(Material.DIAMOND, frame.getItem().getType());
        assertEquals(1, frame.getItem().getAmount());
        assertEquals(WorldWriter.Effect.NONE, adjust(5, 64, 5, "minecraft:emerald", 1));
        assertEquals(WorldWriter.Effect.NONE, adjust(5, 64, 5, "minecraft:emerald", -1));
        assertEquals(Material.DIAMOND, frame.getItem().getType());

        assertEquals(WorldWriter.Effect.ITEMS, adjust(5, 64, 5, "minecraft:diamond", -1));
        assertTrue(frame.getItem().isEmpty());
        assertEquals(WorldWriter.Effect.NONE, adjust(5, 64, 6, "minecraft:diamond", 1));
    }

    @Test
    public void itemsGoOntoAnArmourStandAndOffIt() {
        ArmorStand stand = world.spawn(new Location(world, 7.5, 64, 7.5), ArmorStand.class);

        assertEquals(WorldWriter.Effect.ITEMS, adjust(7, 64, 7, "minecraft:iron_sword", 2));
        assertEquals(Material.IRON_SWORD, stand.getItem(EquipmentSlot.HAND).getType());
        assertEquals(Material.IRON_SWORD, stand.getItem(EquipmentSlot.OFF_HAND).getType());
        assertEquals(WorldWriter.Effect.NONE, adjust(7, 64, 7, "minecraft:stick", 1));

        stand.setItem(EquipmentSlot.HEAD, new ItemStack(Material.DIAMOND_HELMET));
        assertEquals(WorldWriter.Effect.ITEMS, adjust(7, 64, 7, "minecraft:iron_sword", -1));
        assertTrue(stand.getItem(EquipmentSlot.HAND).isEmpty());
        assertEquals(Material.IRON_SWORD, stand.getItem(EquipmentSlot.OFF_HAND).getType());
        assertEquals(WorldWriter.Effect.ITEMS, adjust(7, 64, 7, "minecraft:diamond_helmet", -1));
        assertTrue(stand.getItem(EquipmentSlot.HEAD).isEmpty());
    }

    @Test
    public void itemsGoIntoAStorageMinecartAndOutOfIt() {
        StorageMinecart minecart = world.spawn(new Location(world, 11.5, 64.06, 11.5), StorageMinecart.class);

        assertEquals(WorldWriter.Effect.ITEMS, adjust(11, 64, 11, "minecraft:diamond", 70));
        assertEquals(70, count(minecart.getInventory().getContents(), Material.DIAMOND));
        assertEquals(WorldWriter.Effect.ITEMS, adjust(11, 64, 11, "minecraft:diamond", -65));
        assertEquals(5, count(minecart.getInventory().getContents(), Material.DIAMOND));
        assertEquals(WorldWriter.Effect.NONE, adjust(11, 64, 11, "minecraft:emerald", -1));
        assertEquals(WorldWriter.Effect.NONE, adjust(11, 64, 12, "minecraft:diamond", 1));
    }

    @Test
    public void aContainerBlockComesBeforeAFrameInIt() {
        Block block = world.getBlockAt(9, 64, 9);
        block.setType(Material.CHEST);
        ItemFrame frame = world.spawn(new Location(world, 9.5, 64.5, 9.5), ItemFrame.class);

        assertEquals(WorldWriter.Effect.ITEMS, adjust(9, 64, 9, "minecraft:diamond", 1));

        assertEquals(1, count(block, Material.DIAMOND));
        assertTrue(frame.getItem().isEmpty());
    }

    private Block block(int x, Material material) {
        Block block = world.getBlockAt(x, 64, 0);
        block.setType(material);
        return block;
    }

    private static int held(Block block, Material material) {
        int total = 0;
        for (ItemStack stack : ((TileStateInventoryHolder) block.getState()).getInventory().getContents()) {
            if (stack != null && stack.getType() == material) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    private WorldWriter.Effect adjust(int x, int y, int z, String material, int delta) {
        return writer.apply(world, new PlannedChange.AdjustItems(position(x, y, z), material, new byte[0], delta));
    }

    private static int count(Block block, Material material) {
        return count(((Container) block.getState()).getInventory().getContents(), material);
    }

    private static int count(ItemStack[] contents, Material material) {
        int total = 0;
        for (ItemStack stack : contents) {
            if (stack != null && stack.getType() == material) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    private static long position(int x, int y, int z) {
        return BlockPositions.pack(x, y, z);
    }
}
