package gg.moonrise.hawkeye.entity.importer.source;

import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One row of a CoreProtect event table, with CoreProtect's own ids (see {@link SourceNames}) and its
 * raw codes; a NULL number reads as 0 and a NULL {@code wid} as {@link #NO_WORLD}. Arrays are not
 * copied.
 */
public sealed interface SourceRow {

    /** The world of a row whose {@code wid} is NULL. */
    int NO_WORLD = -1;

    /**
     * The table the row comes from.
     *
     * @return the table
     */
    @NotNull SourceTable table();

    /**
     * The row's rowid in its table.
     *
     * @return the rowid
     */
    long rowId();

    /**
     * When it happened.
     *
     * @return Unix seconds
     */
    long time();

    /**
     * Who did it.
     *
     * @return the {@code user.rowid}, 0 when NULL
     */
    int user();

    /**
     * Where it happened.
     *
     * @return the {@code world.id}, or {@link #NO_WORLD}
     */
    int world();

    /**
     * The block x.
     *
     * @return x
     */
    int x();

    /**
     * The block y.
     *
     * @return y
     */
    int y();

    /**
     * The block z.
     *
     * @return z
     */
    int z();

    /**
     * The cursor just after this row.
     *
     * @return {@code (time, rowId)}
     */
    default @NotNull SourceCursor cursor() {
        return new SourceCursor(time(), rowId());
    }

    /**
     * A {@code block} row: a break (0), place (1), interaction (2), kill (3) or entity spawn (13).
     *
     * @param rowId      the rowid
     * @param time       Unix seconds
     * @param user       the user rowid
     * @param world      the world id
     * @param x          x
     * @param y          y
     * @param z          z
     * @param type       the {@code material_map} id, or for a kill or spawn the {@code entity_map} id
     * @param data       the legacy data value; a kill's {@code entity} rowid (a killed player's user
     *                   rowid when {@code type} is 0); a spawn's {@code entity_spawn} rowid
     * @param blockData  the {@code blockdata_map} ids of the block state, empty when NULL
     * @param action     the action code
     * @param rolledBack the rolled-back bits (bit 1: undone by a rollback)
     * @param entity     the {@code entity_spawn} UUID of a spawn row, when known
     */
    record Block(long rowId, long time, int user, int world, int x, int y, int z, int type, long data,
            int @NotNull [] blockData, int action, int rolledBack, @Nullable UUID entity) implements SourceRow {

        @Override
        public @NotNull SourceTable table() {
            return SourceTable.BLOCK;
        }
    }

    /**
     * A {@code container} or {@code entity_container} row: items removed (0) or added (1).
     *
     * @param table      {@link SourceTable#CONTAINER} or {@link SourceTable#ENTITY_CONTAINER}
     * @param rowId      the rowid
     * @param time       Unix seconds
     * @param user       the user rowid
     * @param world      the world id
     * @param x          x
     * @param y          y
     * @param z          z
     * @param type       the {@code material_map} id of the item
     * @param amount     the item count
     * @param metadata   the item metadata blob (Bukkit object stream), or {@code null}
     * @param action     the action code
     * @param rolledBack the rolled-back bits
     */
    record Container(@NotNull SourceTable table, long rowId, long time, int user, int world, int x, int y, int z,
            int type, int amount, byte @Nullable [] metadata, int action, int rolledBack) implements SourceRow {
    }

    /**
     * An {@code item} row: drops, pickups, ender chest moves, throws, shots, breaks, crafting, trades.
     *
     * @param rowId    the rowid
     * @param time     Unix seconds
     * @param user     the user rowid
     * @param world    the world id
     * @param x        x
     * @param y        y
     * @param z        z
     * @param type     the {@code material_map} id of the item
     * @param amount   the item count
     * @param metadata the item metadata blob ({@code item.data}), or {@code null}
     * @param action   the action code (2 to 12)
     */
    record Item(long rowId, long time, int user, int world, int x, int y, int z, int type, int amount,
            byte @Nullable [] metadata, int action) implements SourceRow {

        @Override
        public @NotNull SourceTable table() {
            return SourceTable.ITEM;
        }
    }

    /**
     * A {@code sign} row: a break (0), place (1), edit (2) or "before" snapshot (3).
     *
     * @param rowId      the rowid
     * @param time       Unix seconds
     * @param user       the user rowid
     * @param world      the world id
     * @param x          x
     * @param y          y
     * @param z          z
     * @param action     the action code
     * @param face       0 for the front, 1 for the back
     * @param lines      {@code line_1} to {@code line_8} (front 1-4, back 5-8), "" for NULL; always 8
     * @param rolledBack 1 when undone by a rollback
     */
    record Sign(long rowId, long time, int user, int world, int x, int y, int z, int action, int face,
            @NotNull List<String> lines, int rolledBack) implements SourceRow {

        /** The number of lines a row carries. */
        public static final int LINES = 8;

        /**
         * Copies the lines.
         *
         * @throws IllegalArgumentException when there are not {@link #LINES} lines
         */
        public Sign {
            lines = List.copyOf(lines);
            if (lines.size() != LINES) {
                throw new IllegalArgumentException("a sign row has " + LINES + " lines, not " + lines.size());
            }
        }

        @Override
        public @NotNull SourceTable table() {
            return SourceTable.SIGN;
        }
    }

    /**
     * A {@code chat} or {@code command} row.
     *
     * @param table   {@link SourceTable#CHAT} or {@link SourceTable#COMMAND}
     * @param rowId   the rowid
     * @param time    Unix seconds
     * @param user    the user rowid
     * @param world   the world id
     * @param x       x
     * @param y       y
     * @param z       z
     * @param message the text, "" for NULL
     */
    record Message(@NotNull SourceTable table, long rowId, long time, int user, int world, int x, int y, int z,
            @NotNull String message) implements SourceRow {
    }

    /**
     * A {@code session} row: a logout (0) or login (1).
     *
     * @param rowId  the rowid
     * @param time   Unix seconds
     * @param user   the user rowid
     * @param world  the world id
     * @param x      x
     * @param y      y
     * @param z      z
     * @param action the action code
     */
    record Session(long rowId, long time, int user, int world, int x, int y, int z, int action) implements SourceRow {

        @Override
        public @NotNull SourceTable table() {
            return SourceTable.SESSION;
        }
    }

    /**
     * An {@code entity_interaction} row: a click (0), shear (1), leash (2) or unleash (3).
     *
     * @param rowId  the rowid
     * @param time   Unix seconds
     * @param user   the user rowid
     * @param world  the world id
     * @param x      x
     * @param y      y
     * @param z      z
     * @param type   the {@code entity_map} id
     * @param action the action code
     */
    record EntityInteraction(long rowId, long time, int user, int world, int x, int y, int z, int type, int action)
            implements SourceRow {

        @Override
        public @NotNull SourceTable table() {
            return SourceTable.ENTITY_INTERACTION;
        }
    }
}
