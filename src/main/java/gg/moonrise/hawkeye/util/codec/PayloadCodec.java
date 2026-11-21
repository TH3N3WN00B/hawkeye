package gg.moonrise.hawkeye.util.codec;

import gg.moonrise.hawkeye.entity.payload.Payload;
import gg.moonrise.hawkeye.entity.payload.SignSides;
import gg.moonrise.hawkeye.util.codec.binary.ByteReader;
import gg.moonrise.hawkeye.util.codec.binary.ByteWriter;
import gg.moonrise.hawkeye.util.codec.binary.CodecException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import org.jetbrains.annotations.NotNull;

/**
 * Encodes {@link Payload}s for the {@code data} column. The first byte is the tag (the payload type
 * in the low bits, {@link #DEFLATED} when the body is deflated); the body follows. Text and sign
 * bodies of at least {@link #COMPRESS_THRESHOLD} bytes are deflated when that makes them smaller.
 * Item and entity bytes (raw NBT, or Paper's gzip NBT in rows from earlier builds) are stored as they
 * are handed over: compressing each one alone would hide the repetition between snapshots from the
 * archive, which compresses many rows together. The format is a data contract: tags are never reused.
 */
public final class PayloadCodec {

    /** Bodies shorter than this are never compressed. */
    public static final int COMPRESS_THRESHOLD = 128;

    /** Decoding refuses bodies that inflate beyond this (a corrupt or hostile row). */
    public static final int MAX_INFLATED_BYTES = 4 * 1024 * 1024;

    private static final int TEXT = 1;
    private static final int SIGN = 2;
    private static final int ITEM = 3;
    private static final int INVENTORY = 4;
    private static final int ENTITY = 5;
    private static final int ENTITY_REF = 6;
    private static final int DEFLATED = 0x80;
    private static final int TYPE_MASK = 0x7F;

    private PayloadCodec() {
    }

    /**
     * Encodes a payload.
     *
     * @param payload the payload
     * @return the tagged bytes
     */
    public static byte @NotNull [] encode(@NotNull Payload payload) {
        return switch (payload) {
            case Payload.Text text -> compressible(TEXT, text.text().getBytes(StandardCharsets.UTF_8));
            case Payload.Sign sign -> {
                ByteWriter body = new ByteWriter(128);
                writeSides(body, sign.before());
                writeSides(body, sign.after());
                yield compressible(SIGN, body.toByteArray());
            }
            case Payload.Item item -> tagged(ITEM, item.bytes());
            case Payload.Inventory inventory -> {
                ByteWriter body = new ByteWriter(64 * inventory.slots().size() + 4);
                body.writeVarLong(inventory.slots().size());
                for (Payload.Slot slot : inventory.slots()) {
                    body.writeVarLong(slot.index()).writeBytes(slot.item());
                }
                yield tagged(INVENTORY, body.toByteArray());
            }
            case Payload.Entity entity -> tagged(ENTITY, entity.bytes());
            case Payload.EntityRef ref -> tagged(ENTITY_REF, new ByteWriter(16)
                    .writeLong(ref.uuid().getMostSignificantBits())
                    .writeLong(ref.uuid().getLeastSignificantBits()).toByteArray());
        };
    }

    /**
     * Decodes what {@link #encode(Payload)} wrote.
     *
     * @param bytes the tagged bytes
     * @return the payload
     * @throws CodecException when the bytes are not a valid payload
     */
    public static @NotNull Payload decode(byte @NotNull [] bytes) {
        ByteReader reader = new ByteReader(bytes);
        int tag = reader.readByte();
        byte[] body = (tag & DEFLATED) != 0 ? inflate(reader.readRemaining()) : reader.readRemaining();
        ByteReader in = new ByteReader(body);
        return switch (tag & TYPE_MASK) {
            case TEXT -> new Payload.Text(new String(body, StandardCharsets.UTF_8));
            case SIGN -> new Payload.Sign(readSides(in), readSides(in));
            case ITEM -> new Payload.Item(body);
            case INVENTORY -> {
                int count = in.readVarInt();
                List<Payload.Slot> slots = new ArrayList<>(Math.min(count, 256));
                for (int i = 0; i < count; i++) {
                    slots.add(new Payload.Slot(in.readVarInt(), in.readBytes()));
                }
                yield new Payload.Inventory(slots);
            }
            case ENTITY -> new Payload.Entity(body);
            case ENTITY_REF -> new Payload.EntityRef(new UUID(in.readLong(), in.readLong()));
            default -> throw new CodecException("unknown payload tag " + tag);
        };
    }

    private static void writeSides(@NotNull ByteWriter out, @NotNull SignSides sides) {
        writeLines(out, sides.front());
        writeLines(out, sides.back());
    }

    private static void writeLines(@NotNull ByteWriter out, @NotNull List<String> lines) {
        out.writeVarLong(lines.size());
        lines.forEach(out::writeString);
    }

    private static @NotNull SignSides readSides(@NotNull ByteReader in) {
        return new SignSides(readLines(in), readLines(in));
    }

    private static @NotNull List<String> readLines(@NotNull ByteReader in) {
        int count = in.readVarInt();
        if (count > 64) {
            throw new CodecException("implausible sign line count " + count);
        }
        List<String> lines = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            lines.add(in.readString());
        }
        return lines;
    }

    private static byte @NotNull [] compressible(int type, byte @NotNull [] body) {
        if (body.length >= COMPRESS_THRESHOLD) {
            byte[] deflated = deflate(body);
            if (deflated.length < body.length) {
                return tagged(type | DEFLATED, deflated);
            }
        }
        return tagged(type, body);
    }

    private static byte @NotNull [] tagged(int tag, byte @NotNull [] body) {
        byte[] out = new byte[body.length + 1];
        out[0] = (byte) tag;
        System.arraycopy(body, 0, out, 1, body.length);
        return out;
    }

    private static byte @NotNull [] deflate(byte @NotNull [] body) {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        try {
            deflater.setInput(body);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(body.length / 2 + 16);
            byte[] buffer = new byte[1024];
            while (!deflater.finished()) {
                out.write(buffer, 0, deflater.deflate(buffer));
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private static byte @NotNull [] inflate(byte @NotNull [] body) {
        Inflater inflater = new Inflater(true);
        try {
            inflater.setInput(body);
            ByteArrayOutputStream out = new ByteArrayOutputStream(body.length * 3 + 16);
            byte[] buffer = new byte[1024];
            while (!inflater.finished()) {
                int read = inflater.inflate(buffer);
                if (read == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    throw new CodecException("truncated deflate body");
                }
                out.write(buffer, 0, read);
                if (out.size() > MAX_INFLATED_BYTES) {
                    throw new CodecException("payload inflates beyond " + MAX_INFLATED_BYTES + " bytes");
                }
            }
            return out.toByteArray();
        } catch (DataFormatException e) {
            throw new CodecException("corrupt deflate body", e);
        } finally {
            inflater.end();
        }
    }
}
