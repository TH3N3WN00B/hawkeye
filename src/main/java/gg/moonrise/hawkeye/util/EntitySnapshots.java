package gg.moonrise.hawkeye.util;

import gg.moonrise.hawkeye.util.nbt.NbtRoot;
import gg.moonrise.hawkeye.util.nbt.NbtTag;
import gg.moonrise.hawkeye.util.nbt.NbtTag.NbtCompound;
import java.util.ArrayList;
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
     * placed in the event's world. {@code current_impulse_context_reset_grace_time}: a knockback timer.
     */
    private static final List<List<String>> ALWAYS = paths(
            "UUID", "Motion", "fall_distance", "Fire", "Air", "OnGround", "PortalCooldown", "HurtTime",
            "HurtByTimestamp", "DeathTime", "Spigot.ticksLived", "Paper.Origin", "Paper.OriginWorld",
            "WorldUUIDLeast", "WorldUUIDMost", "current_impulse_context_reset_grace_time");

    /**
     * Stripped from a dead or dying entity (a kill): its health is zero, and an entity loaded without
     * {@code Health} starts at its maximum, which is what a rolled-back kill should have.
     */
    private static final List<List<String>> DYING = paths("Health");

    /**
     * Stripped from mobs: their exact position and facing. The event row holds the block, and
     * {@code RespawnLocations} puts the respawn at its centre. Other entities (armour stands, displays,
     * item frames, boats) keep both exactly, because builds depend on them.
     */
    private static final List<List<String>> MOB = paths("Pos", "Rotation");

    /**
     * Attribute modifiers vanilla adds at spawn with a random amount and no visible effect (a small
     * follow-range bonus), which alone keep otherwise identical mobs apart.
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
        if (dying || zeroHealth(values)) {
            DYING.forEach(path -> remove(values, path));
        }
        if (mob) {
            MOB.forEach(path -> remove(values, path));
        }
        if (values.get(ATTRIBUTES) instanceof NbtTag.NbtList attributes) {
            values.put(ATTRIBUTES, withoutRandomModifiers(attributes));
        }
        return root.with(new NbtCompound(values));
    }

    private static boolean zeroHealth(@NotNull Map<String, NbtTag> values) {
        return values.get(HEALTH) instanceof NbtTag.NbtFloat health && !(health.value() > 0);
    }

    /** Removes a dotted path; a parent compound left empty by the removal goes too. */
    private static void remove(@NotNull Map<String, NbtTag> values, @NotNull List<String> path) {
        String key = path.getFirst();
        if (path.size() == 1) {
            values.remove(key);
            return;
        }
        if (values.get(key) instanceof NbtCompound child) {
            Map<String, NbtTag> nested = new LinkedHashMap<>(child.values());
            remove(nested, path.subList(1, path.size()));
            if (nested.isEmpty()) {
                values.remove(key);
            } else {
                values.put(key, new NbtCompound(nested));
            }
        }
    }

    private static @NotNull NbtTag.NbtList withoutRandomModifiers(@NotNull NbtTag.NbtList attributes) {
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
        return new NbtTag.NbtList(attributes.elementType(), result);
    }

    private static boolean random(@NotNull NbtTag modifier) {
        return modifier instanceof NbtCompound compound
                && compound.values().get(MODIFIER_ID) instanceof NbtTag.NbtString id
                && RANDOM_MODIFIERS.contains(id.value());
    }

    private static @NotNull List<List<String>> paths(@NotNull String... dotted) {
        List<List<String>> paths = new ArrayList<>(dotted.length);
        for (String path : dotted) {
            paths.add(List.of(path.split("\\.")));
        }
        return List.copyOf(paths);
    }
}
