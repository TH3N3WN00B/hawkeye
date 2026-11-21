package gg.moonrise.hawkeye.core.service.importer.mapping;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.bukkit.Color;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.banner.Pattern;
import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.bukkit.configuration.serialization.ConfigurationSerialization;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BannerMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.SuspiciousStewMeta;
import org.bukkit.potion.PotionEffect;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Rebuilds an item from CoreProtect's metadata lists, in list order: the first map of the first list is
 * {@code ItemMeta#serialize()} (a potion's colour may follow it); later lists hold what CoreProtect took
 * out of the meta: a leather colour, potion or stew effects, banner patterns or a map colour. A list
 * whose first map has {@code modifiers} holds attribute modifiers; lists whose first map has
 * {@code slot} or {@code facing} are CoreProtect bookkeeping. Fireworks and crossbow projectiles keep
 * what the base meta gives.
 */
public final class ItemExtras {

    /** The configuration-serialization alias of item meta. */
    public static final String META_ALIAS = "ItemMeta";

    /** Attribute name prefixes of the versions before 1.21.3 ({@code generic.attack_damage}). */
    private static final Set<String> LEGACY_CATEGORIES = Set.of("generic", "player", "zombie", "horse");

    private ItemExtras() {
    }

    /**
     * Applies the lists to an item.
     *
     * @param item  a new item of the row's material
     * @param lists the decoded lists
     * @throws IllegalArgumentException when a map does not have the shape its place requires
     * @throws IllegalStateException    when the server cannot deserialize item meta
     */
    public static void apply(@NotNull ItemStack item, @NotNull List<List<Map<String, Object>>> lists) {
        for (int index = 0; index < lists.size(); index++) {
            List<Map<String, Object>> list = lists.get(index);
            if (list.isEmpty()) {
                continue;
            }
            Map<String, Object> first = list.getFirst();
            if (first.containsKey("slot") || first.containsKey("facing")) {
                continue;
            }
            if (first.containsKey("modifiers")) {
                modifiers(item, first.get("modifiers"));
            } else if (index == 0) {
                base(item, list);
            } else {
                extra(item, list);
            }
        }
    }

    /**
     * A copy of a map read from a blob with string keys.
     *
     * @param value a value that must be a map
     * @return the copy
     * @throws IllegalArgumentException when the value is not a map
     */
    public static @NotNull Map<String, Object> stringKeys(@Nullable Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("an entry is not a map");
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((key, entry) -> copy.put(String.valueOf(key), entry));
        return copy;
    }

    /**
     * The attribute a CoreProtect key names: {@code minecraft:attack_damage}, an older
     * {@code minecraft:generic.attack_damage} or an enum name such as {@code GENERIC_ATTACK_DAMAGE}.
     *
     * @param key the key
     * @return the attribute, or empty when this server has none by that name
     */
    public static @NotNull Optional<Attribute> attribute(@NotNull String key) {
        String name = key.toLowerCase(Locale.ROOT);
        NamespacedKey parsed = NamespacedKey.fromString(name);
        Attribute exact = parsed == null ? null : Registry.ATTRIBUTE.get(parsed);
        if (exact != null) {
            return Optional.of(exact);
        }
        String path = name.substring(name.indexOf(':') + 1);
        int separator = path.indexOf('.') > 0 ? path.indexOf('.') : path.indexOf('_');
        if (separator > 0 && LEGACY_CATEGORIES.contains(path.substring(0, separator))) {
            NamespacedKey modern = NamespacedKey.fromString(path.substring(separator + 1));
            return modern == null ? Optional.empty() : Optional.ofNullable(Registry.ATTRIBUTE.get(modern));
        }
        return Optional.empty();
    }

    private static void base(@NotNull ItemStack item, @NotNull List<Map<String, Object>> list) {
        Class<? extends ConfigurationSerializable> type = ConfigurationSerialization.getClassByAlias(META_ALIAS);
        if (type == null) {
            throw new IllegalStateException("the server registers no " + META_ALIAS + " deserializer");
        }
        if (!(ConfigurationSerialization.deserializeObject(list.getFirst(), type) instanceof ItemMeta meta)) {
            throw new IllegalArgumentException("the item meta map does not deserialize");
        }
        item.setItemMeta(meta);
        if (list.size() > 1 && item.getItemMeta() instanceof PotionMeta potion) {
            potion.setColor(Color.deserialize(list.get(1)));
            item.setItemMeta(potion);
        }
    }

    @SuppressWarnings("deprecation")
    private static void extra(@NotNull ItemStack item, @NotNull List<Map<String, Object>> list) {
        ItemMeta meta = item.getItemMeta();
        switch (meta) {
            case LeatherArmorMeta leather -> list.forEach(map -> leather.setColor(Color.deserialize(map)));
            case PotionMeta potion -> list.forEach(map -> potion.addCustomEffect(new PotionEffect(map), true));
            case SuspiciousStewMeta stew -> list.forEach(map -> stew.addCustomEffect(new PotionEffect(map), true));
            case BannerMeta banner -> list.forEach(map -> banner.addPattern(pattern(map)));
            case MapMeta map -> list.forEach(colour -> map.setColor(Color.deserialize(colour)));
            case null, default -> {
                return;
            }
        }
        item.setItemMeta(meta);
    }

    @SuppressWarnings("deprecation")
    private static @NotNull Pattern pattern(@NotNull Map<String, Object> map) {
        return new Pattern(map);
    }

    private static void modifiers(@NotNull ItemStack item, @Nullable Object value) {
        if (!(value instanceof List<?> entries)) {
            throw new IllegalArgumentException("attribute modifiers are not a list");
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        for (Object entry : entries) {
            stringKeys(entry).forEach((key, modifier) -> attribute(key).ifPresent(attribute -> {
                try {
                    meta.addAttributeModifier(attribute, AttributeModifier.deserialize(stringKeys(modifier)));
                } catch (IllegalArgumentException e) {
                    // The modifier repeats one the item has, or this server cannot apply it.
                }
            }));
        }
        item.setItemMeta(meta);
    }
}
