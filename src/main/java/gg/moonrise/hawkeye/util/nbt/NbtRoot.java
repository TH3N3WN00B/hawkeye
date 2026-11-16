package gg.moonrise.hawkeye.util.nbt;

import gg.moonrise.hawkeye.util.codec.CodecException;
import org.jetbrains.annotations.NotNull;

/**
 * A whole binary NBT document: the named root compound.
 *
 * @param name     the root's name (Minecraft writes an empty one)
 * @param compound the root compound
 */
public record NbtRoot(@NotNull String name, @NotNull NbtTag.NbtCompound compound) {

    /**
     * Parses a document.
     *
     * @param bytes uncompressed binary NBT
     * @return the root
     * @throws CodecException when the bytes are not valid NBT
     */
    public static @NotNull NbtRoot read(byte @NotNull [] bytes) {
        return NbtReader.read(bytes);
    }

    /**
     * Writes the document.
     *
     * @return uncompressed binary NBT
     */
    public byte @NotNull [] write() {
        return NbtWriter.write(this);
    }

    /**
     * The same document with another root compound.
     *
     * @param replacement the new compound
     * @return a new root with this root's name
     */
    public @NotNull NbtRoot with(@NotNull NbtTag.NbtCompound replacement) {
        return new NbtRoot(name, replacement);
    }
}
