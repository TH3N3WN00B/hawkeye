package gg.moonrise.hawkeye.util.serialize;

import gg.moonrise.hawkeye.util.nbt.NbtGzip;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * Paper's version-safe item serialization, stored as raw NBT. {@code ItemStack#serializeAsBytes}
 * gzips each item on its own, which leaves nothing for the archive's compression to find between
 * identical items; the raw form compresses to a few bytes per repeated item. Reading accepts both
 * forms, so rows written before raw storage keep working.
 */
public final class ItemBytes {

    private ItemBytes() {
    }

    /**
     * Serializes an item.
     *
     * @param item a non-empty item stack
     * @return its raw NBT (with the data version, so the server's data fixers can upgrade it)
     */
    public static byte @NotNull [] serialize(@NotNull ItemStack item) {
        return NbtGzip.raw(item.serializeAsBytes());
    }

    /**
     * Reads an item.
     *
     * @param bytes raw NBT from {@link #serialize(ItemStack)}, or Paper's gzip bytes
     * @return the item stack
     */
    public static @NotNull ItemStack deserialize(byte @NotNull [] bytes) {
        return ItemStack.deserializeBytes(NbtGzip.gzipped(bytes));
    }
}
