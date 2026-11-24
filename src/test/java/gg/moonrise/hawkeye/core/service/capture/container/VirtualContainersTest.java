package gg.moonrise.hawkeye.core.service.capture.container;

import static org.junit.jupiter.api.Assertions.assertEquals;

import gg.moonrise.hawkeye.listener.HeldInventories;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Villager;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.inventory.InventoryMock;

public class VirtualContainersTest {

    private ServerMock server;
    private World world;
    private PlayerMock alex;
    private PlayerMock steve;

    @BeforeEach
    public void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
        alex = server.addPlayer("Alex");
        steve = server.addPlayer("Steve");
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void anEnderChestIsNamedAfterItsOwnerWhenSomeoneElseLooks() {
        assertEquals(Optional.of("ender_chest"), label(HeldInventories.typed(alex, InventoryType.ENDER_CHEST)));
        assertEquals(Optional.of("ender_chest (Steve)"),
                label(HeldInventories.typed(steve, InventoryType.ENDER_CHEST)));
    }

    @Test
    public void anotherPlayersInventoryIsVirtualButTheViewersOwnIsNot() {
        assertEquals(Optional.of("inventory (Steve)"), label(HeldInventories.typed(steve, InventoryType.PLAYER)));
        assertEquals(Optional.empty(), label(HeldInventories.typed(alex, InventoryType.PLAYER)));
    }

    @Test
    public void aPluginInventoryIsLabelledByItsTitleOnOneLine() {
        assertEquals(Optional.of("Vault #1"), label(HeldInventories.chest(null, Component.text("Vault #1"))));
        assertEquals(Optional.of("Backpack of Alex"), label(HeldInventories.chest(new PluginHolder(),
                Component.text("Backpack\nof ").append(Component.text("Alex")))));
        assertEquals(VirtualContainers.MAX_TITLE,
                label(HeldInventories.chest(null, Component.text("x".repeat(100)))).orElseThrow().length());
    }

    @Test
    public void aBlankTitleIsTheTypeName() {
        assertEquals(Optional.of("chest"), label(HeldInventories.chest(null, Component.text(" "))));
        Inventory barrel = HeldInventories.typed(null, InventoryType.BARREL);
        ((InventoryMock) barrel).setCustomTitle(Component.empty());
        assertEquals(Optional.of("barrel"), label(barrel));
    }

    @Test
    public void aContainerInTheWorldIsNotVirtual() {
        Block block = world.getBlockAt(2, 64, -3);
        block.setType(Material.CHEST);
        assertEquals(Optional.empty(), label(HeldInventories.chest((InventoryHolder) block.getState())));
    }

    @Test
    public void anEntitysInventoryIsNotVirtual() {
        Villager villager = world.spawn(new Location(world, 0, 64, 0), Villager.class);
        assertEquals(Optional.empty(), label(HeldInventories.chest(villager, Component.text("Villager"))));
    }

    @Test
    public void inventoriesThatKeepNothingAreNotVirtual() {
        assertEquals(Optional.empty(), label(HeldInventories.typed(null, InventoryType.WORKBENCH)));
        assertEquals(Optional.empty(), label(HeldInventories.typed(null, InventoryType.ANVIL)));
        assertEquals(Optional.empty(), label(HeldInventories.typed(null, InventoryType.FURNACE)));
    }

    private Optional<String> label(@NotNull Inventory inventory) {
        return VirtualContainers.label(alex.openInventory(inventory), alex);
    }

    /** A plugin's own holder, as player vault and backpack plugins use one. */
    private static final class PluginHolder implements InventoryHolder {

        @Override
        public @NotNull Inventory getInventory() {
            throw new UnsupportedOperationException();
        }
    }
}
