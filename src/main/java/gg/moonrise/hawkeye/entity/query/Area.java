package gg.moonrise.hawkeye.entity.query;

import org.jetbrains.annotations.NotNull;

/** Where a lookup, rollback or purge applies. */
public sealed interface Area {

    /** Every world of the selected servers. */
    record Everywhere() implements Area {
    }

    /**
     * One whole world.
     *
     * @param world the world name
     */
    record World(@NotNull String world) implements Area {
    }

    /**
     * An axis-aligned box of blocks (a radius is a box centred on the sender), bounds inclusive.
     *
     * @param world the world name
     * @param minX  smallest x
     * @param minY  smallest y
     * @param minZ  smallest z
     * @param maxX  largest x
     * @param maxY  largest y
     * @param maxZ  largest z
     */
    record Box(@NotNull String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) implements Area {

        /**
         * Validates the bounds.
         *
         * @param world the world name
         * @param minX  smallest x
         * @param minY  smallest y
         * @param minZ  smallest z
         * @param maxX  largest x
         * @param maxY  largest y
         * @param maxZ  largest z
         */
        public Box {
            if (minX > maxX || minY > maxY || minZ > maxZ) {
                throw new IllegalArgumentException("box bounds are inverted");
            }
        }

        /**
         * Whether a block lies inside.
         *
         * @param x block x
         * @param y block y
         * @param z block z
         * @return {@code true} when inside the box
         */
        public boolean contains(int x, int y, int z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }
    }

    /**
     * Exactly one block (the inspector).
     *
     * @param world the world name
     * @param x     block x
     * @param y     block y
     * @param z     block z
     */
    record Block(@NotNull String world, int x, int y, int z) implements Area {
    }
}
