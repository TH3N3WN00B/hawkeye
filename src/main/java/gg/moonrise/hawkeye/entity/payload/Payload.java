package gg.moonrise.hawkeye.entity.payload;

import java.util.Arrays;
import java.util.List;
import org.jetbrains.annotations.NotNull;

/**
 * The optional {@code data} of an event. Item and entity bytes are Paper's own serialization
 * ({@code ItemStack#serializeAsBytes}, {@code UnsafeValues#serializeEntity}), which carries the data
 * version and is upgraded by the server's data fixers, so stored items survive Minecraft updates.
 * Encoded by {@code util.codec.PayloadCodec}.
 */
public sealed interface Payload {

    /**
     * A chat message or command line.
     *
     * @param text the text as typed
     */
    record Text(@NotNull String text) implements Payload {
    }

    /**
     * A sign edit (or the text of a broken sign, with {@code before} empty).
     *
     * @param before the sides before the change
     * @param after  the sides after the change
     */
    record Sign(@NotNull SignSides before, @NotNull SignSides after) implements Payload {
    }

    /**
     * One item stack with components (a plain stack needs no payload: the material and amount columns
     * describe it).
     *
     * @param bytes Paper's item bytes
     */
    record Item(byte @NotNull [] bytes) implements Payload {

        @Override
        public boolean equals(Object other) {
            return other instanceof Item item && Arrays.equals(bytes, item.bytes);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(bytes);
        }

        @Override
        public @NotNull String toString() {
            return "Item[" + bytes.length + " bytes]";
        }
    }

    /**
     * The contents of a container block when it was broken, so a rollback can refill it.
     *
     * @param slots the non-empty slots
     */
    record Inventory(@NotNull List<Slot> slots) implements Payload {

        /**
         * Copies the slot list.
         *
         * @param slots the non-empty slots
         */
        public Inventory {
            slots = List.copyOf(slots);
        }
    }

    /**
     * A killed entity, so a rollback can respawn it.
     *
     * @param bytes Paper's entity bytes
     */
    record Entity(byte @NotNull [] bytes) implements Payload {

        @Override
        public boolean equals(Object other) {
            return other instanceof Entity entity && Arrays.equals(bytes, entity.bytes);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(bytes);
        }

        @Override
        public @NotNull String toString() {
            return "Entity[" + bytes.length + " bytes]";
        }
    }

    /**
     * One inventory slot.
     *
     * @param index the slot index
     * @param item  Paper's item bytes
     */
    record Slot(int index, byte @NotNull [] item) {

        @Override
        public boolean equals(Object other) {
            return other instanceof Slot slot && index == slot.index && Arrays.equals(item, slot.item);
        }

        @Override
        public int hashCode() {
            return 31 * index + Arrays.hashCode(item);
        }

        @Override
        public @NotNull String toString() {
            return "Slot[" + index + ", " + item.length + " bytes]";
        }
    }
}
