package gg.moonrise.hawkeye.core.service.importer.mapping;

import java.util.Optional;
import org.jetbrains.annotations.NotNull;

/**
 * Turns a CoreProtect item metadata blob into the item payload Hawkeye stores. The server's
 * implementation is {@link ServerItemDecoder}; tests pass their own.
 */
public interface ItemDecoder {

    /** Decodes nothing. */
    ItemDecoder NONE = new ItemDecoder() {
        @Override
        public @NotNull Optional<byte[]> decode(@NotNull String material, byte @NotNull [] metadata) {
            return Optional.empty();
        }

        @Override
        public long failures() {
            return 0;
        }
    };

    /**
     * Decodes one item. Never throws.
     *
     * @param material the item's namespaced material, e.g. {@code minecraft:diamond_sword}
     * @param metadata the blob ({@code container.metadata}, {@code item.data})
     * @return the item's bytes ({@code util.ItemBytes}) when it decodes to an item with components;
     *         empty for a plain item or a blob that cannot be decoded
     */
    @NotNull Optional<byte[]> decode(@NotNull String material, byte @NotNull [] metadata);

    /**
     * How many blobs could not be decoded so far.
     *
     * @return the count
     */
    long failures();
}
