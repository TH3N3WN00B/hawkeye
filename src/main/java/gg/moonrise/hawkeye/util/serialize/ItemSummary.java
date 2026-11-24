package gg.moonrise.hawkeye.util.serialize;

import gg.moonrise.hawkeye.util.codec.binary.CodecException;
import gg.moonrise.hawkeye.util.nbt.NbtGzip;
import gg.moonrise.hawkeye.util.nbt.NbtRoot;
import gg.moonrise.hawkeye.util.nbt.NbtTag;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;

/**
 * The material and count of a stored item, read from its NBT without a server ({@link ItemBytes} writes
 * the raw NBT of {@code ItemStack#serializeAsBytes}, whose root holds {@code id} and {@code count}; a
 * missing count means one). Lookups use it to list a snapshot's items without rebuilding each stack.
 *
 * @param id    the namespaced material ({@code minecraft:stone})
 * @param count the stack size
 */
public record ItemSummary(@NotNull String id, int count) {

    /**
     * Reads an item's material and count.
     *
     * @param bytes raw or gzip item NBT
     * @return the summary, or empty when the bytes are not item NBT (another server implementation's
     *         own format, or a corrupt row)
     */
    public static @NotNull Optional<ItemSummary> of(byte @NotNull [] bytes) {
        NbtTag.NbtCompound item;
        try {
            item = NbtRoot.read(NbtGzip.raw(bytes)).compound();
        } catch (CodecException e) {
            return Optional.empty();
        }
        if (!(item.get("id").orElse(null) instanceof NbtTag.NbtString id) || id.value().isEmpty()) {
            return Optional.empty();
        }
        int count = switch (item.get("count").orElse(null)) {
            case NbtTag.NbtInt value -> value.value();
            case NbtTag.NbtShort value -> value.value();
            case NbtTag.NbtByte value -> value.value();
            case null, default -> 1;
        };
        return Optional.of(new ItemSummary(id.value(), count));
    }
}
