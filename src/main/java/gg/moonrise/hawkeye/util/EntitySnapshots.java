package gg.moonrise.hawkeye.util;

import gg.moonrise.hawkeye.util.nbt.NbtRoot;
import gg.moonrise.hawkeye.util.nbt.NbtTag;
import gg.moonrise.hawkeye.util.nbt.NbtTag.NbtCompound;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.NotNull;

/**
 * Strips an entity snapshot down to what a respawn needs. A snapshot exists so a rollback can put the
 * entity back; tags that the event row already records, that a respawn resets anyway, or that vanilla
 * rolled at random make otherwise identical snapshots differ and defeat the archive's compression.
 * Every stripped tag is optional when Minecraft loads an entity and falls back to the default of a
 * newly created one. Nothing a player would notice after a rollback is touched: age and baby state,
 * variants, colours, names, equipment and drop chances, villager data and trades, owners, saddles,
 * chests, effects, persistence, AI flags, scoreboard tags and passengers all stay.
 */
public final class EntitySnapshots {

    /**
     * Stripped from every snapshot. {@code UUID}: a respawn always gets a fresh one. {@code Motion},
     * {@code fall_distance}, {@code Fire}, {@code Air}, {@code OnGround}, {@code PortalCooldown},
     * {@code HurtTime}, {@code HurtByTimestamp}, {@code DeathTime}: momentary physics and damage state;
     * the respawn should stand at rest, not burning, not falling, not mid-hurt animation.
     * {@code Spigot.ticksLived}, {@code Paper.Origin}, {@code Paper.OriginWorld}, {@code WorldUUIDLeast},
     * {@code WorldUUIDMost}: bookkeeping about where and when the original spawned; the respawn is
     * placed in the event's world. Paper writes these with a dot in the key itself, at the top level.
     * {@code current_impulse_context_reset_grace_time}: a knockback timer.
     */
    private static final List<List<String>> ALWAYS = keys(
            "UUID", "Motion", "fall_distance", "Fire", "Air", "OnGround", "PortalCooldown", "HurtTime",
            "HurtByTimestamp", "DeathTime", "Spigot.ticksLived", "Paper.Origin", "Paper.OriginWorld",
            "WorldUUIDLeast", "WorldUUIDMost", "current_impulse_context_reset_grace_time");

    /**
     * Stripped from every snapshot: the fight that ended in the kill. Who hurt it last and how long ago
     * ({@code last_hurt_by_mob}, {@code ticks_since_last_hurt_by_mob}, {@code last_hurt_by_player},
     * {@code last_hurt_by_player_memory_time}) and a neutral mob's anger ({@code angry_at},
     * {@code anger_end_time}, {@code AngerTime}, {@code AngryAt}): a respawned golem, wolf or bee should
     * not resume the fight with whoever killed it. All are optional and absent on a calm mob.
     */
    private static final List<List<String>> COMBAT = keys(
            "last_hurt_by_mob", "ticks_since_last_hurt_by_mob", "last_hurt_by_player",
            "last_hurt_by_player_memory_time", "angry_at", "anger_end_time", "AngerTime", "AngryAt");

    /**
     * Stripped from every snapshot: countdowns vanilla rolls at random for every new entity anyway
     * ({@code EggLayTime}, a chicken's next egg) and short-lived brain memories that expire within
     * seconds ({@code golem_detected_recently}, a villager's panic timer). Villager gossip, job site,
     * home and meeting point memories stay.
     */
    private static final List<List<String>> REROLLED = List.of(
            List.of("EggLayTime"),
            List.of("Brain", "memories", "minecraft:golem_detected_recently"));

    /**
     * Stripped from a dead or dying entity (a kill): its health is zero, and an entity loaded without
     * {@code Health} starts at its maximum, which is what a rolled-back kill should have.
     */
    private static final List<List<String>> DYING = keys("Health");

    /**
     * Stripped from mobs: their exact position and facing. The event row holds the block, and
     * {@code RespawnLocations} puts the respawn at its centre. Other entities (armour stands, displays,
     * item frames, boats) keep both exactly, because builds depend on them.
     */
    private static final List<List<String>> MOB = keys("Pos", "Rotation");

    /**
     * Attribute modifiers vanilla adds at spawn with a random amount and no visible effect (a small
     * follow-range bonus on naturally spawned mobs), which alone keep otherwise identical mobs apart.
     */
    private static final Set<String> RANDOM_MODIFIERS = Set.of("minecraft:random_spawn_bonus");

    private static final String ATTRIBUTES = "attributes";
    private static final String MODIFIERS = "modifiers";
    private static final String MODIFIER_ID = "id";
    private static final String HEALTH = "Health";

    private EntitySnapshots() {
    }

    /**
     * Normalizes a snapshot.
     *
     * @param root  the entity's NBT
     * @param mob   whether the entity is a mob (position and rotation are stripped)
     * @param dying whether the entity is dead or dying (its health is stripped); an entity whose
     *              {@code Health} is zero or less counts as dying too
     * @return the normalized NBT
     */
    public static @NotNull NbtRoot normalize(@NotNull NbtRoot root, boolean mob, boolean dying) {
        Map<String, NbtTag> values = new LinkedHashMap<>(root.compound().values());
        ALWAYS.forEach(path -> remove(values, path));
        COMBAT.forEach(path -> remove(values, path));
        REROLLED.forEach(path -> remove(values, path));
        if (dying || zeroHealth(values)) {
            DYING.forEach(path -> remove(values, path));
        }
        if (mob) {
            MOB.forEach(path -> remove(values, path));
        }
        if (values.get(ATTRIBUTES) instanceof NbtTag.NbtList attributes) {
            values.put(ATTRIBUTES, normalizeAttributes(attributes));
        }
        return root.with(new NbtCompound(values));
    }

    private static boolean zeroHealth(@NotNull Map<String, NbtTag> values) {
        return values.get(HEALTH) instanceof NbtTag.NbtFloat health && !(health.value() > 0);
    }

    /**
     * Removes a path of keys. Parent compounds stay, even when left empty: some (a brain's
     * {@code memories}) are required when the entity is loaded.
     */
    private static void remove(@NotNull Map<String, NbtTag> values, @NotNull List<String> path) {
        String key = path.getFirst();
        if (path.size() == 1) {
            values.remove(key);
            return;
        }
        if (values.get(key) instanceof NbtCompound child) {
            Map<String, NbtTag> nested = new LinkedHashMap<>(child.values());
            remove(nested, path.subList(1, path.size()));
            values.put(key, new NbtCompound(nested));
        }
    }

    /**
     * Drops random spawn modifiers and sorts the attributes by id. Paper writes them in hash-map order,
     * which differs between otherwise identical mobs; Minecraft reads each attribute by its id, so the
     * order carries nothing.
     */
    private static @NotNull NbtTag.NbtList normalizeAttributes(@NotNull NbtTag.NbtList attributes) {
        if (attributes.elementType() != NbtTag.COMPOUND) {
            return attributes;
        }
        List<NbtTag> result = new ArrayList<>(attributes.values().size());
        for (NbtTag element : attributes.values()) {
            NbtCompound attribute = (NbtCompound) element;
            if (attribute.values().get(MODIFIERS) instanceof NbtTag.NbtList modifiers
                    && modifiers.elementType() == NbtTag.COMPOUND) {
                List<NbtTag> kept = modifiers.values().stream().filter(modifier -> !random(modifier)).toList();
                Map<String, NbtTag> values = new LinkedHashMap<>(attribute.values());
                if (kept.isEmpty()) {
                    // The modifier list is optional; vanilla omits it when empty.
                    values.remove(MODIFIERS);
                } else {
                    values.put(MODIFIERS, new NbtTag.NbtList(NbtTag.COMPOUND, kept));
                }
                result.add(new NbtCompound(values));
            } else {
                result.add(attribute);
            }
        }
        result.sort(Comparator.comparing(EntitySnapshots::attributeId));
        return new NbtTag.NbtList(attributes.elementType(), result);
    }

    private static @NotNull String attributeId(@NotNull NbtTag attribute) {
        return ((NbtCompound) attribute).values().get(MODIFIER_ID) instanceof NbtTag.NbtString id ? id.value() : "";
    }

    private static boolean random(@NotNull NbtTag modifier) {
        return modifier instanceof NbtCompound compound
                && compound.values().get(MODIFIER_ID) instanceof NbtTag.NbtString id
                && RANDOM_MODIFIERS.contains(id.value());
    }

    /** Top-level keys, taken literally (Paper's own keys contain dots). */
    private static @NotNull List<List<String>> keys(@NotNull String... keys) {
        return Arrays.stream(keys).map(List::of).toList();
    }
}
