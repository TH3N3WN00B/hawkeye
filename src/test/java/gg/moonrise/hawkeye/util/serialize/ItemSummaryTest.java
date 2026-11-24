package gg.moonrise.hawkeye.util.serialize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.util.nbt.NbtGzip;
import gg.moonrise.hawkeye.util.nbt.NbtRoot;
import gg.moonrise.hawkeye.util.nbt.NbtTag;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

public class ItemSummaryTest {

    @Test
    public void readsTheMaterialAndCountOfRawAndGzipItemNbt() {
        byte[] raw = item("minecraft:diamond", 12);
        assertEquals(Optional.of(new ItemSummary("minecraft:diamond", 12)), ItemSummary.of(raw));
        assertEquals(Optional.of(new ItemSummary("minecraft:diamond", 12)), ItemSummary.of(NbtGzip.gzip(raw)));
    }

    @Test
    public void aMissingCountIsOne() {
        Map<String, NbtTag> values = new LinkedHashMap<>();
        values.put("id", new NbtTag.NbtString("minecraft:elytra"));
        values.put("DataVersion", new NbtTag.NbtInt(4800));
        byte[] bytes = new NbtRoot("", new NbtTag.NbtCompound(values)).write();
        assertEquals(Optional.of(new ItemSummary("minecraft:elytra", 1)), ItemSummary.of(bytes));
    }

    @Test
    public void bytesThatAreNotItemNbtHaveNoSummary() {
        assertTrue(ItemSummary.of(new byte[] {(byte) 0xac, (byte) 0xed, 0, 5}).isEmpty(), "Java serialization");
        assertTrue(ItemSummary.of(new byte[] {10, 0}).isEmpty(), "truncated NBT");
        assertTrue(ItemSummary.of(new byte[0]).isEmpty(), "no bytes");
        byte[] withoutId = new NbtRoot("", new NbtTag.NbtCompound(Map.of("count", new NbtTag.NbtInt(3)))).write();
        assertTrue(ItemSummary.of(withoutId).isEmpty(), "no id");
    }

    /**
     * The raw NBT of an item as Paper writes it: {@code id}, {@code count} and the data version.
     *
     * @param id    the material
     * @param count the stack size
     * @return the bytes
     */
    public static byte @NotNull [] item(@NotNull String id, int count) {
        Map<String, NbtTag> values = new LinkedHashMap<>();
        values.put("id", new NbtTag.NbtString(id));
        values.put("count", new NbtTag.NbtInt(count));
        values.put("DataVersion", new NbtTag.NbtInt(4800));
        return new NbtRoot("", new NbtTag.NbtCompound(values)).write();
    }
}
