package gg.moonrise.hawkeye.util.codec;

import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.hawkeye.entity.actor.ActorKind;
import gg.moonrise.hawkeye.entity.event.PendingEvent;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.NotNull;

/**
 * The spool file format: events that could not be written before shutdown, kept with their names
 * and stamps so the next start writes them exactly as they would have been written.
 * {@code "HKS1", count, events...}.
 */
public final class SpoolCodec {

    private static final byte[] MAGIC = {'H', 'K', 'S', '1'};

    private SpoolCodec() {
    }

    /**
     * Encodes events.
     *
     * @param events the events
     * @return the file contents
     */
    public static byte @NotNull [] encode(@NotNull List<PendingEvent> events) {
        ByteWriter out = new ByteWriter(64 * events.size() + 16);
        out.writeRaw(MAGIC).writeVarLong(events.size());
        for (PendingEvent event : events) {
            out.writeLong(event.stamp())
                    .writeString(event.world())
                    .writeLong(event.position())
                    .writeByte(event.actor().kind().code())
                    .writeString(event.actor().key())
                    .writeString(event.actor().name())
                    .writeVarLong(event.action().code())
                    .writeString(event.subject());
            if (event.secondary() == null) {
                out.writeByte(0);
            } else {
                out.writeByte(1).writeString(event.secondary());
            }
            out.writeVarLong(Math.max(0, event.amount()));
            if (event.data() == null) {
                out.writeByte(0);
            } else {
                out.writeByte(1).writeBytes(event.data());
            }
        }
        return out.toByteArray();
    }

    /**
     * Decodes a spool file.
     *
     * @param bytes the file contents
     * @return the events
     * @throws CodecException when the file is not a valid spool file
     */
    public static @NotNull List<PendingEvent> decode(byte @NotNull [] bytes) {
        ByteReader in = new ByteReader(bytes);
        for (byte expected : MAGIC) {
            if (in.readByte() != (expected & 0xFF)) {
                throw new CodecException("not a Hawkeye spool file");
            }
        }
        int count = in.readVarInt();
        List<PendingEvent> events = new ArrayList<>(Math.min(count, 1 << 16));
        for (int i = 0; i < count; i++) {
            long stamp = in.readLong();
            String world = in.readString();
            long position = in.readLong();
            int kindCode = in.readByte();
            ActorKind kind = ActorKind.byCode(kindCode)
                    .orElseThrow(() -> new CodecException("unknown actor kind " + kindCode));
            Actor actor = new Actor(kind, in.readString(), in.readString());
            int actionCode = in.readVarInt();
            Action action = Action.byCode(actionCode)
                    .orElseThrow(() -> new CodecException("unknown action " + actionCode));
            String subject = in.readString();
            String secondary = in.readByte() == 1 ? in.readString() : null;
            int amount = in.readVarInt();
            byte[] data = in.readByte() == 1 ? in.readBytes() : null;
            events.add(new PendingEvent(stamp, world, position, actor, action, subject, secondary, amount, data));
        }
        return events;
    }
}
