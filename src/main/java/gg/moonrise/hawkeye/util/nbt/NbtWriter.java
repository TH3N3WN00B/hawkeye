package gg.moonrise.hawkeye.util.nbt;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Map;
import org.jetbrains.annotations.NotNull;

/** Writes uncompressed binary NBT; the inverse of {@link NbtReader}. */
public final class NbtWriter {

    private NbtWriter() {
    }

    /**
     * Writes a document.
     *
     * @param root the root
     * @return uncompressed binary NBT
     * @throws IllegalArgumentException when a string is longer than NBT allows (65535 encoded bytes)
     */
    public static byte @NotNull [] write(@NotNull NbtRoot root) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(256);
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeByte(NbtTag.COMPOUND);
            out.writeUTF(root.name());
            writePayload(out, root.compound());
        } catch (IOException e) {
            // UTFDataFormatException (a string too long) is the only failure of an in-memory stream.
            throw new IllegalArgumentException("NBT cannot hold this tree", e);
        }
        return buffer.toByteArray();
    }

    private static void writePayload(@NotNull DataOutputStream out, @NotNull NbtTag tag) throws IOException {
        switch (tag) {
            case NbtTag.NbtByte value -> out.writeByte(value.value());
            case NbtTag.NbtShort value -> out.writeShort(value.value());
            case NbtTag.NbtInt value -> out.writeInt(value.value());
            case NbtTag.NbtLong value -> out.writeLong(value.value());
            case NbtTag.NbtFloat value -> out.writeInt(value.bits());
            case NbtTag.NbtDouble value -> out.writeLong(value.bits());
            case NbtTag.NbtString value -> out.writeUTF(value.value());
            case NbtTag.NbtByteArray value -> {
                byte[] values = value.values();
                out.writeInt(values.length);
                out.write(values);
            }
            case NbtTag.NbtIntArray value -> {
                int[] values = value.values();
                out.writeInt(values.length);
                for (int element : values) {
                    out.writeInt(element);
                }
            }
            case NbtTag.NbtLongArray value -> {
                long[] values = value.values();
                out.writeInt(values.length);
                for (long element : values) {
                    out.writeLong(element);
                }
            }
            case NbtTag.NbtList list -> {
                out.writeByte(list.elementType());
                out.writeInt(list.values().size());
                for (NbtTag element : list.values()) {
                    writePayload(out, element);
                }
            }
            case NbtTag.NbtCompound compound -> {
                for (Map.Entry<String, NbtTag> entry : compound.values().entrySet()) {
                    out.writeByte(entry.getValue().id());
                    out.writeUTF(entry.getKey());
                    writePayload(out, entry.getValue());
                }
                out.writeByte(NbtTag.END);
            }
        }
    }
}
