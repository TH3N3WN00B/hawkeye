package gg.moonrise.hawkeye.util.nbt;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;

/**
 * One binary NBT tag (Java Edition format), immutable. {@link NbtReader} reads it and
 * {@link NbtWriter} writes it back; unchanged input round-trips byte for byte.
 */
public sealed interface NbtTag {

    /** The end tag id: closes a compound, and is the element type of an untyped empty list. */
    int END = 0;
    /** The byte tag id. */
    int BYTE = 1;
    /** The short tag id. */
    int SHORT = 2;
    /** The int tag id. */
    int INT = 3;
    /** The long tag id. */
    int LONG = 4;
    /** The float tag id. */
    int FLOAT = 5;
    /** The double tag id. */
    int DOUBLE = 6;
    /** The byte array tag id. */
    int BYTE_ARRAY = 7;
    /** The string tag id. */
    int STRING = 8;
    /** The list tag id. */
    int LIST = 9;
    /** The compound tag id. */
    int COMPOUND = 10;
    /** The int array tag id. */
    int INT_ARRAY = 11;
    /** The long array tag id. */
    int LONG_ARRAY = 12;

    /**
     * The tag's type id.
     *
     * @return 1 to 12
     */
    int id();

    /**
     * A byte.
     *
     * @param value the value
     */
    record NbtByte(byte value) implements NbtTag {
        @Override
        public int id() {
            return BYTE;
        }
    }

    /**
     * A short.
     *
     * @param value the value
     */
    record NbtShort(short value) implements NbtTag {
        @Override
        public int id() {
            return SHORT;
        }
    }

    /**
     * An int.
     *
     * @param value the value
     */
    record NbtInt(int value) implements NbtTag {
        @Override
        public int id() {
            return INT;
        }
    }

    /**
     * A long.
     *
     * @param value the value
     */
    record NbtLong(long value) implements NbtTag {
        @Override
        public int id() {
            return LONG;
        }
    }

    /**
     * A float, kept as its raw bits so every value (NaN payloads included) round-trips exactly.
     *
     * @param bits the IEEE 754 bits
     */
    record NbtFloat(int bits) implements NbtTag {

        /**
         * A float from its value.
         *
         * @param value the value
         * @return the tag
         */
        public static @NotNull NbtFloat of(float value) {
            return new NbtFloat(Float.floatToRawIntBits(value));
        }

        /**
         * The value.
         *
         * @return the float
         */
        public float value() {
            return Float.intBitsToFloat(bits);
        }

        @Override
        public int id() {
            return FLOAT;
        }
    }

    /**
     * A double, kept as its raw bits.
     *
     * @param bits the IEEE 754 bits
     */
    record NbtDouble(long bits) implements NbtTag {

        /**
         * A double from its value.
         *
         * @param value the value
         * @return the tag
         */
        public static @NotNull NbtDouble of(double value) {
            return new NbtDouble(Double.doubleToRawLongBits(value));
        }

        /**
         * The value.
         *
         * @return the double
         */
        public double value() {
            return Double.longBitsToDouble(bits);
        }

        @Override
        public int id() {
            return DOUBLE;
        }
    }

    /**
     * A string (written as modified UTF-8).
     *
     * @param value the value
     */
    record NbtString(@NotNull String value) implements NbtTag {
        @Override
        public int id() {
            return STRING;
        }
    }

    /**
     * A byte array.
     *
     * @param values the bytes, copied
     */
    record NbtByteArray(byte @NotNull [] values) implements NbtTag {

        /**
         * Copies the array.
         *
         * @param values the bytes
         */
        public NbtByteArray {
            values = values.clone();
        }

        @Override
        public byte @NotNull [] values() {
            return values.clone();
        }

        /**
         * The length without copying.
         *
         * @return the element count
         */
        public int length() {
            return values.length;
        }

        @Override
        public int id() {
            return BYTE_ARRAY;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof NbtByteArray array && Arrays.equals(values, array.values);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(values);
        }

        @Override
        public @NotNull String toString() {
            return "NbtByteArray" + Arrays.toString(values);
        }
    }

    /**
     * An int array.
     *
     * @param values the ints, copied
     */
    record NbtIntArray(int @NotNull [] values) implements NbtTag {

        /**
         * Copies the array.
         *
         * @param values the ints
         */
        public NbtIntArray {
            values = values.clone();
        }

        @Override
        public int @NotNull [] values() {
            return values.clone();
        }

        @Override
        public int id() {
            return INT_ARRAY;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof NbtIntArray array && Arrays.equals(values, array.values);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(values);
        }

        @Override
        public @NotNull String toString() {
            return "NbtIntArray" + Arrays.toString(values);
        }
    }

    /**
     * A long array.
     *
     * @param values the longs, copied
     */
    record NbtLongArray(long @NotNull [] values) implements NbtTag {

        /**
         * Copies the array.
         *
         * @param values the longs
         */
        public NbtLongArray {
            values = values.clone();
        }

        @Override
        public long @NotNull [] values() {
            return values.clone();
        }

        @Override
        public int id() {
            return LONG_ARRAY;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof NbtLongArray array && Arrays.equals(values, array.values);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(values);
        }

        @Override
        public @NotNull String toString() {
            return "NbtLongArray" + Arrays.toString(values);
        }
    }

    /**
     * A list of tags of one type. The element type is kept for empty lists too ({@link #END} or the
     * type the writer declared), so they round-trip exactly.
     *
     * @param elementType the element type id
     * @param values      the elements, copied
     */
    record NbtList(int elementType, @NotNull List<NbtTag> values) implements NbtTag {

        /**
         * Copies the elements and checks that they all have the element type.
         *
         * @param elementType the element type id
         * @param values      the elements
         */
        public NbtList {
            values = List.copyOf(values);
            if (elementType < END || elementType > LONG_ARRAY) {
                throw new IllegalArgumentException("unknown element type " + elementType);
            }
            for (NbtTag value : values) {
                if (value.id() != elementType) {
                    throw new IllegalArgumentException("a list of type " + elementType + " holds a " + value.id());
                }
            }
        }

        /**
         * A list of elements of one type.
         *
         * @param elementType the element type id
         * @param values      the elements
         * @return the list
         */
        public static @NotNull NbtList of(int elementType, @NotNull NbtTag... values) {
            return new NbtList(elementType, List.of(values));
        }

        @Override
        public int id() {
            return LIST;
        }
    }

    /**
     * A compound: named tags in insertion order.
     *
     * @param values the entries, copied in their iteration order
     */
    record NbtCompound(@NotNull Map<String, NbtTag> values) implements NbtTag {

        /**
         * Copies the entries, keeping their order.
         *
         * @param values the entries
         */
        public NbtCompound {
            values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }

        /**
         * One entry.
         *
         * @param name the key
         * @return the tag, or empty
         */
        public @NotNull Optional<NbtTag> get(@NotNull String name) {
            return Optional.ofNullable(values.get(name));
        }

        /**
         * A nested compound.
         *
         * @param name the key
         * @return the compound, or empty when the key is missing or holds another type
         */
        public @NotNull Optional<NbtCompound> compound(@NotNull String name) {
            return values.get(name) instanceof NbtCompound compound ? Optional.of(compound) : Optional.empty();
        }

        @Override
        public int id() {
            return COMPOUND;
        }
    }
}
