package gg.moonrise.hawkeye.core.service.importer.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.util.ItemBytes;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InvalidClassException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.banner.Pattern;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BannerMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.SuspiciousStewMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

/**
 * CoreProtect's item blobs, built the way its {@code ItemMetaHandler} writes them, decoded on
 * MockBukkit.
 */
public class ServerItemDecoderTest {

    private final ServerItemDecoder decoder = new ServerItemDecoder();

    @BeforeEach
    public void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void aNamedEnchantedItemKeepsItsMeta() throws IOException {
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta meta = sword.getItemMeta();
        meta.displayName(Component.text("Excalibur"));
        meta.lore(List.of(Component.text("old")));
        meta.addEnchant(Enchantment.SHARPNESS, 5, true);
        sword.setItemMeta(meta);

        ItemStack decoded = decoded("minecraft:diamond_sword", blob(lists(List.of(meta.serialize()))));

        assertEquals(Material.DIAMOND_SWORD, decoded.getType());
        assertEquals(1, decoded.getAmount());
        assertEquals(Component.text("Excalibur"), decoded.getItemMeta().displayName());
        assertEquals(List.of(Component.text("old")), decoded.getItemMeta().lore());
        assertEquals(5, decoded.getEnchantmentLevel(Enchantment.SHARPNESS));
        assertEquals(0, decoder.failures());
    }

    @Test
    public void aPotionGetsItsColourAndCustomEffects() throws IOException {
        PotionMeta base = (PotionMeta) new ItemStack(Material.POTION).getItemMeta();
        PotionEffect speed = new PotionEffect(PotionEffectType.SPEED, 200, 1);
        PotionEffect haste = new PotionEffect(PotionEffectType.HASTE, 100, 0);

        // MockBukkit's item bytes drop a potion's colour, so the rebuilt item is checked before serializing.
        ItemStack decoded = rebuilt("minecraft:potion", blob(lists(List.of(base.serialize(),
                Color.fromRGB(0x123456).serialize()), List.of(speed.serialize()), List.of(haste.serialize()))));

        PotionMeta meta = (PotionMeta) decoded.getItemMeta();
        assertEquals(Color.fromRGB(0x123456), meta.getColor());
        assertEquals(List.of(speed, haste), meta.getCustomEffects());
    }

    @Test
    public void leatherArmourGetsItsColour() throws IOException {
        LeatherArmorMeta base = (LeatherArmorMeta) new ItemStack(Material.LEATHER_HELMET).getItemMeta();

        ItemStack decoded = decoded("minecraft:leather_helmet", blob(lists(List.of(base.serialize()),
                List.of(Color.fromRGB(0xAA0000).serialize()))));

        assertEquals(Color.fromRGB(0xAA0000), ((LeatherArmorMeta) decoded.getItemMeta()).getColor());
    }

    @Test
    public void aSuspiciousStewGetsItsEffects() throws IOException {
        SuspiciousStewMeta base = (SuspiciousStewMeta) new ItemStack(Material.SUSPICIOUS_STEW).getItemMeta();
        PotionEffect night = new PotionEffect(PotionEffectType.NIGHT_VISION, 100, 0);

        ItemStack decoded = decoded("minecraft:suspicious_stew", blob(lists(List.of(base.serialize()),
                List.of(night.serialize()))));

        assertEquals(List.of(night), ((SuspiciousStewMeta) decoded.getItemMeta()).getCustomEffects());
    }

    @Test
    @SuppressWarnings("deprecation")
    public void aBannerGetsItsPatterns() throws IOException {
        BannerMeta base = (BannerMeta) new ItemStack(Material.WHITE_BANNER).getItemMeta();
        Pattern stripe = new Pattern(DyeColor.RED, Registry.BANNER_PATTERN.getOrThrow(
                NamespacedKey.minecraft("stripe_top")));

        ItemStack decoded = decoded("minecraft:white_banner", blob(lists(List.of(base.serialize()),
                List.of(stripe.serialize()))));

        assertEquals(List.of(stripe), ((BannerMeta) decoded.getItemMeta()).getPatterns());
    }

    @Test
    @SuppressWarnings("deprecation")
    public void aMapGetsItsColour() throws IOException {
        MapMeta base = (MapMeta) new ItemStack(Material.FILLED_MAP).getItemMeta();

        ItemStack decoded = decoded("minecraft:filled_map", blob(lists(List.of(base.serialize()),
                List.of(Color.fromRGB(0x00FF00).serialize()))));

        assertEquals(Color.fromRGB(0x00FF00), ((MapMeta) decoded.getItemMeta()).getColor());
    }

    @Test
    public void attributeModifiersAreReapplied() throws IOException {
        ItemMeta base = new ItemStack(Material.IRON_SWORD).getItemMeta();
        base.displayName(Component.text("Heavy"));
        AttributeModifier modifier = new AttributeModifier(NamespacedKey.minecraft("heavy"), 3.0,
                AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND);
        Map<Object, Map<String, Object>> entry = new HashMap<>();
        entry.put("minecraft:attack_damage", modifier.serialize());
        Map<String, Object> modifiers = new HashMap<>();
        modifiers.put("modifiers", new ArrayList<>(List.of(entry)));

        // MockBukkit cannot serialize an item with attribute modifiers (AttributeMock is not Serializable).
        ItemStack decoded = rebuilt("minecraft:iron_sword", blob(lists(List.of(base.serialize()),
                List.of(modifiers))));

        assertEquals(List.of(modifier), List.copyOf(decoded.getItemMeta().getAttributeModifiers(
                Attribute.ATTACK_DAMAGE)));
    }

    @Test
    public void attributeKeysOfOlderVersionsResolve() {
        assertEquals(Optional.of(Attribute.ATTACK_DAMAGE), ItemExtras.attribute("minecraft:attack_damage"));
        assertEquals(Optional.of(Attribute.ATTACK_DAMAGE), ItemExtras.attribute("minecraft:generic.attack_damage"));
        assertEquals(Optional.of(Attribute.ATTACK_DAMAGE), ItemExtras.attribute("GENERIC_ATTACK_DAMAGE"));
        assertEquals(Optional.empty(), ItemExtras.attribute("minecraft:no_such_attribute"));
    }

    @Test
    public void bookkeepingListsAreIgnored() throws IOException {
        ItemMeta meta = new ItemStack(Material.STICK).getItemMeta();
        meta.displayName(Component.text("Wand"));

        ItemStack decoded = decoded("minecraft:stick", blob(lists(List.of(meta.serialize()),
                List.of(map("slot", 3)), List.of(map("facing", "NORTH")), List.of())));

        assertEquals(Component.text("Wand"), decoded.getItemMeta().displayName());
        assertEquals(0, decoder.failures());
    }

    @Test
    public void anItemWithoutComponentsHasNoPayload() throws IOException {
        assertEquals(Optional.empty(), decoder.decode("minecraft:stone", blob(new ArrayList<>())));
        assertEquals(0, decoder.failures());
    }

    @Test
    public void aBlobWithAClassOutsideTheAllowListIsRejected() throws IOException {
        List<Object> hostile = new ArrayList<>();
        hostile.add(new ArrayList<>(List.of(new HashMap<>(Map.of("file", new File("server.properties"))))));

        assertEquals(Optional.empty(), decoder.decode("minecraft:stone", blob(hostile)));
        assertEquals(1, decoder.failures());
    }

    @Test
    @SuppressWarnings("deprecation")
    public void theFilterRejectsAForeignClassBeforeConstructingIt() throws IOException {
        List<Object> hostile = new ArrayList<>(List.of(new File("server.properties")));
        try (BukkitObjectInputStream in = new BukkitObjectInputStream(new ByteArrayInputStream(blob(hostile)))) {
            in.setObjectInputFilter(ServerItemDecoder.FILTER);
            assertThrows(InvalidClassException.class, in::readObject);
        }
    }

    @Test
    public void truncatedOversizedAndForeignBlobsFailQuietly() throws IOException {
        ItemMeta meta = new ItemStack(Material.STICK).getItemMeta();
        meta.displayName(Component.text("Wand"));
        byte[] blob = blob(lists(List.of(meta.serialize())));

        assertEquals(Optional.empty(), decoder.decode("minecraft:stick", Arrays.copyOf(blob, blob.length / 2)));
        assertEquals(Optional.empty(), decoder.decode("minecraft:stick", new byte[ServerItemDecoder.MAX_BYTES + 1]));
        assertEquals(Optional.empty(), decoder.decode("minecraft:stick", new byte[] {1, 2, 3}));
        assertEquals(Optional.empty(), decoder.decode("minecraft:no_such_item", blob));
        assertEquals(Optional.empty(), decoder.decode("minecraft:stick", blob("not a list")));
        assertEquals(5, decoder.failures());
    }

    private @NotNull ItemStack decoded(@NotNull String material, byte @NotNull [] blob) {
        Optional<byte[]> bytes = decoder.decode(material, blob);
        assertTrue(bytes.isPresent(), "the blob decodes (failures: " + decoder.failures() + ")");
        return ItemBytes.deserialize(bytes.get());
    }

    private @NotNull ItemStack rebuilt(@NotNull String material, byte @NotNull [] blob) {
        Optional<ItemStack> item = decoder.rebuild(material, blob);
        assertTrue(item.isPresent(), "the blob decodes (failures: " + decoder.failures() + ")");
        assertEquals(0, decoder.failures());
        return item.get();
    }

    @SafeVarargs
    private static @NotNull List<List<Map<String, Object>>> lists(@NotNull List<Map<String, Object>>... lists) {
        List<List<Map<String, Object>>> out = new ArrayList<>();
        for (List<Map<String, Object>> list : lists) {
            List<Map<String, Object>> copy = new ArrayList<>();
            list.forEach(map -> copy.add(new HashMap<>(map)));
            out.add(copy);
        }
        return out;
    }

    private static @NotNull Map<String, Object> map(@NotNull String key, @NotNull Object value) {
        Map<String, Object> map = new HashMap<>();
        map.put(key, value);
        return map;
    }

    @SuppressWarnings("deprecation")
    private static byte @NotNull [] blob(@NotNull Object value) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (BukkitObjectOutputStream out = new BukkitObjectOutputStream(bytes)) {
            out.writeObject(value);
        }
        return bytes.toByteArray();
    }
}
