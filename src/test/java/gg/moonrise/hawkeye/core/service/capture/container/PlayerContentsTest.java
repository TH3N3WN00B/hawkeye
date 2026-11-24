package gg.moonrise.hawkeye.core.service.capture.container;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import gg.moonrise.hawkeye.entity.payload.Payload;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

public class PlayerContentsTest {

    private ServerMock server;

    @BeforeEach
    public void setUp() {
        server = MockBukkit.mock();
        server.addSimpleWorld("world");
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void storageArmourOffHandAndCursorKeepTheirSlotIndexes() {
        PlayerMock player = server.addPlayer("Alex");
        player.getInventory().setItem(0, new ItemStack(Material.STONE, 64));
        player.getInventory().setItem(35, new ItemStack(Material.DIRT, 2));
        player.getInventory().setHelmet(new ItemStack(Material.IRON_HELMET));
        player.getInventory().setBoots(new ItemStack(Material.LEATHER_BOOTS));
        player.getInventory().setItemInOffHand(new ItemStack(Material.SHIELD));
        player.setItemOnCursor(new ItemStack(Material.TORCH, 3));

        PlayerContents contents = PlayerContents.of(player);

        assertEquals(List.of(0, 35, 36, 39, 40, Payload.Snapshot.CURSOR),
                contents.slots().stream().map(Payload.Slot::index).sorted().toList());
        assertEquals(64 + 2 + 1 + 1 + 1 + 3, contents.amount());
    }

    @Test
    public void anEmptyInventoryHasNoSlots() {
        PlayerContents contents = PlayerContents.of(server.addPlayer("Alex"));
        assertEquals(List.of(), contents.slots());
        assertEquals(0, contents.amount());
    }

    @Test
    public void anItemThatCannotBeSerializedIsLeftOutAndTheRestIsKept() {
        ItemStack broken = new ItemStack(Material.IRON_SWORD);
        ItemMeta meta = broken.getItemMeta();
        meta.addAttributeModifier(Attribute.ATTACK_DAMAGE, new AttributeModifier(NamespacedKey.minecraft("heavy"),
                3.0, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
        broken.setItemMeta(meta);
        // MockBukkit cannot serialize an item with attribute modifiers (AttributeMock is not Serializable).
        assertThrows(RuntimeException.class, broken::serializeAsBytes);

        PlayerContents contents = PlayerContents.of(new ItemStack[] {new ItemStack(Material.APPLE, 5), broken, null,
                new ItemStack(Material.AIR)}, null);

        assertEquals(List.of(0), contents.slots().stream().map(Payload.Slot::index).toList());
        assertEquals(5, contents.amount());
    }
}
