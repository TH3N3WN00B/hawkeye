package gg.moonrise.hawkeye.entity.payload;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * The optional {@code data} of an event. Item and entity bytes are the NBT of Paper's own serialization
 * ({@code ItemStack#serializeAsBytes}, {@code UnsafeValues#serializeEntity}), which carries the data
 * version and is upgraded by the server's data fixers, so stored items survive Minecraft updates. They
 * are stored uncompressed ({@code util.serialize.ItemBytes}, {@code util.serialize.EntityBytes}; entity snapshots
 * normalized by {@code util.serialize.EntitySnapshots}) so the archive's compression sees what repeats between
 * them; rows from earlier builds hold Paper's gzip form, which reads the same. Encoded by
 * {@code util.codec.PayloadCodec}.
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
     * A sign edit, or the text of a broken sign ({@code before} holds the text, {@code after} is empty).
     * A blank side is an empty line list.
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
     * @param bytes the item's NBT
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
     * @param bytes the entity's NBT
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
     * A reference to an entity that still exists (a placed or spawned one), so a rollback can find and
     * remove it.
     *
     * @param uuid the entity's UUID
     */
    record EntityRef(@NotNull UUID uuid) implements Payload {
    }

    /**
     * A copy of a player's whole inventory at a moment (join, quit, teleport, death), with what the
     * moment was. Slot indexes are those of {@code PlayerInventory#getContents()} (0 to 35 storage, 36 to
     * 39 armour, 40 the off hand), and {@link #CURSOR} is the stack held on the cursor.
     *
     * @param detail what the moment was (where a teleport went, the death message), or empty
     * @param slots  the non-empty slots
     */
    record Snapshot(@NotNull String detail, @NotNull List<Slot> slots) implements Payload {

        /** The slot index of the stack on the cursor. */
        public static final int CURSOR = 41;

        /**
         * Copies the slot list.
         *
         * @param detail what the moment was, or empty
         * @param slots  the non-empty slots
         */
        public Snapshot {
            slots = List.copyOf(slots);
        }
    }

    /**
     * One inventory slot.
     *
     * @param index the slot index
     * @param item  the item's NBT
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
