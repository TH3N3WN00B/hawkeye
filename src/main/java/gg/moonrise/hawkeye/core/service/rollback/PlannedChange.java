package gg.moonrise.hawkeye.core.service.rollback;

import gg.moonrise.hawkeye.entity.payload.Payload;
import gg.moonrise.hawkeye.util.BlockPositions;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/** One change a plan makes to the world at a block position. */
public sealed interface PlannedChange {

    /**
     * Where.
     *
     * @return the packed block position
     */
    long position();

    /**
     * Block x.
     *
     * @return x
     */
    default int x() {
        return BlockPositions.x(position());
    }

    /**
     * Block y.
     *
     * @return y
     */
    default int y() {
        return BlockPositions.y(position());
    }

    /**
     * Block z.
     *
     * @return z
     */
    default int z() {
        return BlockPositions.z(position());
    }

    /**
     * Sets a block state, then optionally its sign text and container contents.
     *
     * @param position the packed position
     * @param state    the block state ({@code minecraft:oak_stairs[facing=east,...]})
     * @param sign     the sign text to write, {@link SignPatch#NONE} for none
     * @param contents the container slots to fill, empty for none
     */
    record SetBlock(long position, @NotNull String state, @NotNull SignPatch sign, @NotNull List<Payload.Slot> contents)
            implements PlannedChange {

        /**
         * Copies the slots.
         *
         * @param position the packed position
         * @param state    the block state
         * @param sign     the sign text
         * @param contents the container slots
         */
        public SetBlock {
            contents = List.copyOf(contents);
        }
    }

    /**
     * Rewrites the text of the sign already there, keeping its block state.
     *
     * @param position the packed position
     * @param sign     the text
     */
    record SetSignText(long position, @NotNull SignPatch sign) implements PlannedChange {
    }

    /**
     * Puts items into ({@code delta > 0}) or takes them out of ({@code delta < 0}) the container there.
     *
     * @param position the packed position
     * @param material the item material ({@code minecraft:diamond})
     * @param item     Paper's item bytes for an item with components, or empty for a plain material
     * @param delta    how many
     */
    record AdjustItems(long position, @NotNull String material, byte @NotNull [] item, int delta)
            implements PlannedChange {

        @Override
        public boolean equals(Object other) {
            return other instanceof AdjustItems that && position == that.position && delta == that.delta
                    && material.equals(that.material) && Arrays.equals(item, that.item);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * (31 * Long.hashCode(position) + material.hashCode()) + Arrays.hashCode(item)) + delta;
        }

        @Override
        public @NotNull String toString() {
            return "AdjustItems[" + position + ", " + material + ", " + item.length + " bytes, " + delta + "]";
        }
    }

    /**
     * Spawns a killed or removed entity again.
     *
     * @param position the packed position it died or was removed at
     * @param entity   Paper's entity bytes
     */
    record SpawnEntity(long position, byte @NotNull [] entity) implements PlannedChange {

        @Override
        public boolean equals(Object other) {
            return other instanceof SpawnEntity that && position == that.position && Arrays.equals(entity, that.entity);
        }

        @Override
        public int hashCode() {
            return 31 * Long.hashCode(position) + Arrays.hashCode(entity);
        }

        @Override
        public @NotNull String toString() {
            return "SpawnEntity[" + position + ", " + entity.length + " bytes]";
        }
    }

    /**
     * Removes a placed or spawned entity, found by its UUID wherever it went in the world. The change
     * runs on the region of the position it was spawned at and only removes the entity when that region
     * owns it.
     *
     * @param position the packed position it was spawned at
     * @param entity   the entity's UUID
     */
    record RemoveEntity(long position, @NotNull UUID entity) implements PlannedChange {
    }
}
