package gg.moonrise.hawkeye.util;

import static gg.moonrise.hawkeye.util.nbt.NbtReaderTest.compound;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.util.nbt.NbtRoot;
import gg.moonrise.hawkeye.util.nbt.NbtTag;
import gg.moonrise.hawkeye.util.nbt.NbtTag.NbtByte;
import gg.moonrise.hawkeye.util.nbt.NbtTag.NbtCompound;
import gg.moonrise.hawkeye.util.nbt.NbtTag.NbtDouble;
import gg.moonrise.hawkeye.util.nbt.NbtTag.NbtFloat;
import gg.moonrise.hawkeye.util.nbt.NbtTag.NbtInt;
import gg.moonrise.hawkeye.util.nbt.NbtTag.NbtIntArray;
import gg.moonrise.hawkeye.util.nbt.NbtTag.NbtList;
import gg.moonrise.hawkeye.util.nbt.NbtTag.NbtLong;
import gg.moonrise.hawkeye.util.nbt.NbtTag.NbtShort;
import gg.moonrise.hawkeye.util.nbt.NbtTag.NbtString;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

public class EntitySnapshotsTest {

    @Test
    public void stripsMomentaryStateFromEveryEntity() {
        NbtCompound normalized = EntitySnapshots.normalize(zombie(20f, 1), false, false).compound();
        for (String key : List.of("UUID", "Motion", "fall_distance", "Fire", "Air", "OnGround", "PortalCooldown",
                "HurtTime", "HurtByTimestamp", "DeathTime", "WorldUUIDLeast", "WorldUUIDMost",
                "current_impulse_context_reset_grace_time", "Spigot.ticksLived", "Paper.Origin",
                "Paper.OriginWorld", "last_hurt_by_mob", "ticks_since_last_hurt_by_mob", "angry_at",
                "anger_end_time", "EggLayTime")) {
            assertFalse(normalized.values().containsKey(key), key);
        }
        assertTrue(normalized.values().containsKey("Paper.SpawnReason"));
        NbtCompound memories = normalized.compound("Brain").orElseThrow().compound("memories").orElseThrow();
        assertEquals(Set.of("minecraft:home"), memories.values().keySet());
    }

    @Test
    public void keepsWhatAPlayerWouldMiss() {
        NbtCompound normalized = EntitySnapshots.normalize(zombie(20f, 1), true, true).compound();
        for (String key : List.of("id", "DataVersion", "CustomName", "IsBaby", "equipment", "drop_chances",
                "PersistenceRequired", "NoAI", "Silent", "Invulnerable", "Tags", "active_effects", "CanPickUpLoot")) {
            assertTrue(normalized.values().containsKey(key), key);
        }
    }

    @Test
    public void mobsLosePositionAndRotationOtherEntitiesKeepThem() {
        NbtCompound mob = EntitySnapshots.normalize(zombie(20f, 1), true, false).compound();
        assertFalse(mob.values().containsKey("Pos"));
        assertFalse(mob.values().containsKey("Rotation"));
        NbtCompound stand = EntitySnapshots.normalize(zombie(20f, 1), false, false).compound();
        assertEquals(zombie(20f, 1).compound().get("Pos"), stand.get("Pos"));
        assertEquals(zombie(20f, 1).compound().get("Rotation"), stand.get("Rotation"));
    }

    @Test
    public void healthIsStrippedOnlyFromTheDying() {
        assertTrue(EntitySnapshots.normalize(zombie(13f, 1), true, false).compound().values().containsKey("Health"));
        assertFalse(EntitySnapshots.normalize(zombie(13f, 1), true, true).compound().values().containsKey("Health"));
        assertFalse(EntitySnapshots.normalize(zombie(0f, 1), true, false).compound().values().containsKey("Health"));
    }

    @Test
    public void randomSpawnBonusesGoOtherModifiersStay() {
        NbtList attributes = (NbtList) EntitySnapshots.normalize(zombie(20f, 1), true, true).compound()
                .get("attributes").orElseThrow();
        NbtCompound followRange = (NbtCompound) attributes.values().get(0);
        assertFalse(followRange.values().containsKey("modifiers"));
        assertEquals(new NbtDouble(Double.doubleToRawLongBits(35.0)), followRange.get("base").orElseThrow());
        NbtCompound health = (NbtCompound) attributes.values().get(1);
        NbtList modifiers = (NbtList) health.get("modifiers").orElseThrow();
        assertEquals(List.of("minecraft:leader_zombie_bonus"), modifiers.values().stream()
                .map(modifier -> ((NbtString) ((NbtCompound) modifier).get("id").orElseThrow()).value()).toList());
    }

    @Test
    public void attributesAreSortedById() {
        NbtRoot shuffled = new NbtRoot("", compound("attributes", NbtList.of(NbtTag.COMPOUND,
                compound("id", new NbtString("minecraft:movement_speed"), "base", NbtDouble.of(0.23)),
                compound("id", new NbtString("minecraft:armor"), "base", NbtDouble.of(2)),
                compound("id", new NbtString("minecraft:max_health"), "base", NbtDouble.of(20)))));
        NbtList attributes = (NbtList) EntitySnapshots.normalize(shuffled, true, false).compound().get("attributes")
                .orElseThrow();
        assertEquals(List.of("minecraft:armor", "minecraft:max_health", "minecraft:movement_speed"),
                attributes.values().stream()
                        .map(attribute -> ((NbtString) ((NbtCompound) attribute).get("id").orElseThrow()).value())
                        .toList());
    }

    @Test
    public void identicalMobsBecomeIdenticalSnapshots() {
        byte[] first = EntitySnapshots.normalize(zombie(0f, 1), true, true).write();
        byte[] second = EntitySnapshots.normalize(zombie(0f, 2), true, true).write();
        assertArrayEquals(first, second);
    }

    @Test
    public void leavesAnUntouchedSnapshotByteIdentical() {
        NbtRoot minimal = new NbtRoot("", compound("id", new NbtString("minecraft:armor_stand"),
                "Pos", NbtList.of(NbtTag.DOUBLE, NbtDouble.of(1.3), NbtDouble.of(-60), NbtDouble.of(2.7))));
        assertArrayEquals(minimal.write(), EntitySnapshots.normalize(minimal, false, true).write());
    }

    /** A zombie as Paper serializes it; {@code seed} varies every field that differs between two zombies. */
    private static NbtRoot zombie(float health, int seed) {
        NbtCompound followRange = compound("id", new NbtString("minecraft:follow_range"), "base", NbtDouble.of(35.0),
                "modifiers", NbtList.of(NbtTag.COMPOUND, compound("id", new NbtString("minecraft:random_spawn_bonus"),
                        "amount", NbtDouble.of(0.01 * seed), "operation", new NbtString("add_multiplied_base"))));
        NbtCompound maxHealth = compound("id", new NbtString("minecraft:max_health"), "base", NbtDouble.of(20.0),
                "modifiers", NbtList.of(NbtTag.COMPOUND, compound("id", new NbtString("minecraft:leader_zombie_bonus"),
                        "amount", NbtDouble.of(0.5), "operation", new NbtString("add_multiplied_total")),
                        compound("id", new NbtString("minecraft:random_spawn_bonus"), "amount", NbtDouble.of(seed),
                                "operation", new NbtString("add_value"))));
        return new NbtRoot("", compound(
                "DataVersion", new NbtInt(4700),
                "id", new NbtString("minecraft:zombie"),
                "Pos", NbtList.of(NbtTag.DOUBLE, NbtDouble.of(1000.5 + seed), NbtDouble.of(-60), NbtDouble.of(1006.2)),
                "Motion", NbtList.of(NbtTag.DOUBLE, NbtDouble.of(0.01 * seed), NbtDouble.of(-0.08), NbtDouble.of(0)),
                "Rotation", NbtList.of(NbtTag.FLOAT, NbtFloat.of(12f * seed), NbtFloat.of(0f)),
                "fall_distance", NbtDouble.of(0.5 * seed),
                "Fire", new NbtShort((short) -seed),
                "Air", new NbtShort((short) 300),
                "OnGround", new NbtByte((byte) (seed % 2)),
                "Invulnerable", new NbtByte((byte) 0),
                "PortalCooldown", new NbtInt(seed),
                "UUID", new NbtIntArray(new int[] {seed, 2, 3, 4}),
                "CustomName", new NbtString("Bob"),
                "Silent", new NbtByte((byte) 0),
                "NoAI", new NbtByte((byte) 0),
                "Tags", NbtList.of(NbtTag.STRING, new NbtString("farm")),
                "current_impulse_context_reset_grace_time", new NbtInt(seed),
                "Paper.SpawnReason", new NbtString("NATURAL"),
                "Paper.Origin", NbtList.of(NbtTag.DOUBLE, NbtDouble.of(seed), NbtDouble.of(-60), NbtDouble.of(1)),
                "Paper.OriginWorld", new NbtIntArray(new int[] {seed, 1, 1, 1}),
                "Spigot.ticksLived", new NbtInt(100 * seed),
                "last_hurt_by_mob", new NbtIntArray(new int[] {seed, 5, 5, 5}),
                "ticks_since_last_hurt_by_mob", new NbtInt(seed),
                "angry_at", new NbtIntArray(new int[] {seed, 6, 6, 6}),
                "anger_end_time", new NbtLong(1000L * seed),
                "EggLayTime", new NbtInt(6000 + seed),
                "Brain", compound("memories", compound("minecraft:golem_detected_recently", compound("value",
                        new NbtByte((byte) 1), "ttl", new NbtLong(seed)), "minecraft:home", compound("value",
                        new NbtString("home")))),
                "WorldUUIDLeast", new NbtByte((byte) seed),
                "WorldUUIDMost", new NbtByte((byte) seed),
                "Health", NbtFloat.of(health),
                "HurtTime", new NbtShort((short) seed),
                "HurtByTimestamp", new NbtInt(seed * 7),
                "DeathTime", new NbtShort((short) seed),
                "attributes", NbtList.of(NbtTag.COMPOUND, followRange, maxHealth),
                "active_effects", NbtList.of(NbtTag.COMPOUND, compound("id", new NbtString("minecraft:speed"))),
                "equipment", compound("head", compound("id", new NbtString("minecraft:diamond_helmet"))),
                "drop_chances", compound("head", NbtFloat.of(0f)),
                "CanPickUpLoot", new NbtByte((byte) 0),
                "PersistenceRequired", new NbtByte((byte) 1),
                "IsBaby", new NbtByte((byte) 0)));
    }
}
