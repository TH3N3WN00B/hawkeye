package gg.moonrise.hawkeye.listener.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.core.service.capture.Actors;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.listener.Recorded;
import gg.moonrise.hawkeye.support.TestPlugins;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.ExplosionResult;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.TNTPrimeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

public class TntListenerTest {

    private ServerMock server;
    private World world;
    private PlayerMock player;

    @BeforeEach
    public void setUp() {
        server = MockBukkit.mock();
        TestPlugins.load();
        world = server.addSimpleWorld("world");
        player = server.addPlayer("Alex");
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void tntLitByAPlayerIsABreakByThatPlayer() {
        Block tnt = tnt(0);
        prime(tnt, TNTPrimeEvent.PrimeCause.PLAYER, player);

        StoredEvent event = Recorded.only(Action.BLOCK_BREAK);
        assertEquals("Alex", event.actor().name());
        assertTrue(event.subject().startsWith("minecraft:tnt"), event.subject());
    }

    @Test
    public void tntLitByRedstoneCountsForItsPlacerAndSoDoesItsExplosion() {
        Block tnt = world.getBlockAt(0, 64, 0);
        BlockState replaced = tnt.getState();
        tnt.setType(Material.TNT);
        server.getPluginManager().callEvent(new BlockPlaceEvent(tnt, replaced, tnt.getRelative(BlockFace.DOWN),
                new ItemStack(Material.TNT), player, true, EquipmentSlot.HAND));
        prime(tnt, TNTPrimeEvent.PrimeCause.REDSTONE, null);
        tnt.setType(Material.AIR);
        TNTPrimed primed = spawnPrimed(tnt);
        assertEquals("Alex", Actors.taggedCause(primed).orElseThrow().name());

        Block stone = world.getBlockAt(3, 64, 0);
        stone.setType(Material.STONE);
        server.getPluginManager().callEvent(new EntityExplodeEvent(primed, primed.getLocation(),
                new ArrayList<>(List.of(stone)), 1, ExplosionResult.DESTROY));

        List<StoredEvent> breaks = Recorded.events(Action.BLOCK_BREAK);
        assertEquals(2, breaks.size());
        assertTrue(breaks.get(0).subject().startsWith("minecraft:tnt"), breaks.get(0).subject());
        assertTrue(breaks.stream().allMatch(event -> event.actor().name().equals("Alex")), breaks.toString());
    }

    @Test
    public void tntNobodyPlacedIsTheTntAndItsEntityIsNotTagged() {
        Block tnt = tnt(0);
        prime(tnt, TNTPrimeEvent.PrimeCause.FIRE, null);
        tnt.setType(Material.AIR);
        TNTPrimed primed = spawnPrimed(tnt);

        assertEquals("#tnt", Recorded.only(Action.BLOCK_BREAK).actor().name());
        assertTrue(Actors.taggedCause(primed).isEmpty());
    }

    @Test
    public void tntSetOffByATaggedExplosionCountsForTheSamePlayerAndIsNotRecordedTwice() {
        Block first = tnt(0);
        Block second = tnt(4);
        TNTPrimed lit = world.spawn(new Location(world, 0.5, 64, 0.5), TNTPrimed.class);
        Actors.tag(lit, Actors.of(player));
        server.getPluginManager().callEvent(new EntityExplodeEvent(lit, lit.getLocation(),
                new ArrayList<>(List.of(second)), 1, ExplosionResult.DESTROY));
        prime(second, TNTPrimeEvent.PrimeCause.EXPLOSION, lit);
        second.setType(Material.AIR);
        TNTPrimed chained = spawnPrimed(second);

        StoredEvent event = Recorded.only(Action.BLOCK_BREAK);
        assertEquals("Alex", event.actor().name());
        assertEquals(4, event.x());
        assertEquals("Alex", Actors.taggedCause(chained).orElseThrow().name());
        assertEquals(Material.TNT, first.getType(), "the first block was never touched");
    }

    @Test
    public void aDispenserPrimingInFrontOfItBreaksNothing() {
        Block front = world.getBlockAt(0, 64, 0);
        front.setType(Material.SHORT_GRASS);
        prime(front, TNTPrimeEvent.PrimeCause.DISPENSER, null);

        assertTrue(Recorded.events(Action.BLOCK_BREAK).isEmpty());
    }

    private void prime(Block block, TNTPrimeEvent.PrimeCause cause, Entity igniter) {
        server.getPluginManager().callEvent(new TNTPrimeEvent(block, cause, igniter, null));
    }

    private TNTPrimed spawnPrimed(Block block) {
        TNTPrimed primed = world.spawn(block.getLocation().add(0.5, 0, 0.5), TNTPrimed.class);
        server.getPluginManager().callEvent(new EntitySpawnEvent(primed));
        return primed;
    }

    private Block tnt(int x) {
        Block block = world.getBlockAt(x, 64, 0);
        block.setType(Material.TNT);
        return block;
    }
}
