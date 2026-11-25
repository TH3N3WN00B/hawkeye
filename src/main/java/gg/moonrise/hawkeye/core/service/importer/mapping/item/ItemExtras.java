package gg.moonrise.hawkeye.core.service.importer.mapping.item;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.banner.Pattern;
import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.bukkit.configuration.serialization.ConfigurationSerialization;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BannerMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.CrossbowMeta;
import org.bukkit.inventory.meta.FireworkEffectMeta;
import org.bukkit.inventory.meta.FireworkMeta;
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
 * out of the meta: a leather colour, potion or stew effects, banner patterns, a map colour, or the items in
 * a bundle or loaded in a crossbow, each a map of {@code ItemStack#serialize()} without its meta under
 * {@code "0"} and that item's own lists under {@code "1"}. A firework effect takes three lists: a map of
 * its {@code type}, {@code flicker} and {@code trail}, then its colours, then its fade colours (often an
 * empty list). A list whose first map has {@code modifiers} holds attribute modifiers; lists whose first
 * map has {@code slot} or {@code facing} are CoreProtect bookkeeping.
 */
@Slf4j
public final class ItemExtras {

    /** The configuration-serialization alias of item meta. */
    public static final String META_ALIAS = "ItemMeta";

    /** The key of the first map of a firework effect's lists. */
    private static final String EFFECT_KEY = "flicker";

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
     * @throws IllegalStateException    when the server cannot deserialize item meta, or a firework effect
     *                                  has no colour
     */
    public static void apply(@NotNull ItemStack item, @NotNull List<List<Map<String, Object>>> lists) {
        int index = 0;
        while (index < lists.size()) {
            List<Map<String, Object>> list = lists.get(index);
            Map<String, Object> first = list.isEmpty() ? Map.of() : list.getFirst();
            if (first.containsKey(EFFECT_KEY)) {
                effect(item, first, listAt(lists, index + 1), listAt(lists, index + 2));
                index += 3;
                continue;
            }
            if (first.containsKey("modifiers")) {
                modifiers(item, first.get("modifiers"));
            } else if (list.isEmpty() || first.containsKey("slot") || first.containsKey("facing")) {
                index++;
                continue;
            } else if (index == 0) {
                base(item, list);
            } else {
                extra(item, list);
            }
            index++;
        }
    }

    /**
     * Checks the shape CoreProtect writes and copies it with types the rest can rely on.
     *
     * @param root what the blob holds (or what an item inside another holds under {@code "1"})
     * @return the lists, every map with string keys
     * @throws IllegalArgumentException when it is not a list of lists of maps
     */
    public static @NotNull List<List<Map<String, Object>>> lists(@Nullable Object root) {
        if (!(root instanceof List<?> outer)) {
            throw new IllegalArgumentException("not a list but " + (root == null ? "null"
                    : root.getClass().getSimpleName()));
        }
        List<List<Map<String, Object>>> lists = new ArrayList<>(outer.size());
        for (Object inner : outer) {
            if (!(inner instanceof List<?> maps)) {
                throw new IllegalArgumentException("an entry is not a list (an older CoreProtect format)");
            }
            List<Map<String, Object>> list = new ArrayList<>(maps.size());
            for (Object map : maps) {
                list.add(stringKeys(map));
            }
            lists.add(list);
        }
        return lists;
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
            case BundleMeta bundle -> list.forEach(map -> contained(item, map).ifPresent(bundle::addItem));
            case CrossbowMeta crossbow -> list.forEach(map -> contained(item, map)
                    .ifPresent(crossbow::addChargedProjectile));
            case null, default -> {
                return;
            }
        }
        item.setItemMeta(meta);
    }

    /**
     * An item CoreProtect stored inside another ({@code ItemUtils.serializeItemStack}). One this server cannot
     * rebuild is left out and the container keeps the rest, as in CoreProtect's own rollback.
     */
    private static @NotNull Optional<ItemStack> contained(@NotNull ItemStack container,
            @NotNull Map<String, Object> map) {
        try {
            ItemStack inside = ItemStack.deserialize(stringKeys(map.get("0")));
            apply(inside, lists(map.getOrDefault("1", List.of())));
            return inside.getType().isAir() ? Optional.empty() : Optional.of(inside);
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.debug("Left out an item inside a {}: {}", container.getType().getKey(), e.toString());
            return Optional.empty();
        }
    }

    private static void effect(@NotNull ItemStack item, @NotNull Map<String, Object> flags,
            @NotNull List<Map<String, Object>> colours, @NotNull List<Map<String, Object>> fades) {
        ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof FireworkMeta) && !(meta instanceof FireworkEffectMeta)) {
            return;
        }
        FireworkEffect effect = FireworkEffect.builder()
                .with(flags.get("type") instanceof FireworkEffect.Type type ? type : FireworkEffect.Type.BALL)
                .flicker(Boolean.TRUE.equals(flags.get(EFFECT_KEY)))
                .trail(Boolean.TRUE.equals(flags.get("trail")))
                .withColor(colours.stream().map(Color::deserialize).toList())
                .withFade(fades.stream().map(Color::deserialize).toList())
                .build();
        if (meta instanceof FireworkMeta rocket) {
            rocket.addEffect(effect);
        } else {
            ((FireworkEffectMeta) meta).setEffect(effect);
        }
        item.setItemMeta(meta);
    }

    private static @NotNull List<Map<String, Object>> listAt(@NotNull List<List<Map<String, Object>>> lists,
            int index) {
        return index < lists.size() ? lists.get(index) : List.of();
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
