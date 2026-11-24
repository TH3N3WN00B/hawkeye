package gg.moonrise.hawkeye.util.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.entity.payload.Payload;
import gg.moonrise.hawkeye.entity.payload.SignSides;
import gg.moonrise.hawkeye.util.codec.binary.CodecException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

public class PayloadCodecTest {

    @Test
    public void roundTripsEveryPayloadType() {
        List<Payload> payloads = List.of(
                new Payload.Text("hello world"),
                new Payload.Text(""),
                new Payload.Sign(SignSides.EMPTY, new SignSides(List.of("{\"text\":\"a\"}", "b"), List.of("c"))),
                new Payload.Item(new byte[] {1, 2, 3}),
                new Payload.Inventory(List.of(new Payload.Slot(0, new byte[] {9}), new Payload.Slot(26,
                        new byte[] {8, 7}))),
                new Payload.Entity(new byte[] {4, 5}),
                new Payload.EntityRef(UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e")),
                new Payload.Snapshot("ender_pearl to world_nether 10 64 -5", List.of(new Payload.Slot(0,
                        new byte[] {10, 0, 0}), new Payload.Slot(40, new byte[] {6}),
                        new Payload.Slot(Payload.Snapshot.CURSOR, new byte[] {5, 4}))),
                new Payload.Snapshot("", List.of(new Payload.Slot(36, new byte[] {1}))),
                new Payload.Snapshot("", List.of()),
                new Payload.VirtualItem("ender_chest (Steve)", new byte[] {10, 0, 0, 8}),
                new Payload.VirtualItem("Vault #1", new byte[0]),
                new Payload.VirtualItem("", new byte[0]));
        for (Payload payload : payloads) {
            assertEquals(payload, PayloadCodec.decode(PayloadCodec.encode(payload)), payload.toString());
        }
    }

    @Test
    public void longTextIsDeflated() {
        String text = "/give @a minecraft:diamond 64 ".repeat(40);
        byte[] encoded = PayloadCodec.encode(new Payload.Text(text));
        assertTrue(encoded.length < text.length() / 4, "deflated to " + encoded.length + " bytes");
        assertEquals(new Payload.Text(text), PayloadCodec.decode(encoded));
    }

    @Test
    public void shortTextIsStoredAsItIs() {
        byte[] encoded = PayloadCodec.encode(new Payload.Text("hi"));
        assertArrayEquals(new byte[] {1, 'h', 'i'}, encoded);
    }

    @Test
    public void aSnapshotIsTaggedSevenAndKeepsItsItemBytesAsTheyAre() {
        byte[] item = {10, 0, 0, 8, 0, 2, 'i', 'd', 0, 1, 'x', 0};
        byte[] encoded = PayloadCodec.encode(new Payload.Snapshot("x".repeat(300),
                List.of(new Payload.Slot(3, item), new Payload.Slot(4, item))));
        assertEquals(7, encoded[0]);
        assertTrue(encoded.length > 300 + 2 * item.length, "not deflated: " + encoded.length + " bytes");
        assertArrayEquals(new byte[] {7, 0, 0}, PayloadCodec.encode(new Payload.Snapshot("", List.of())));
    }

    @Test
    public void aVirtualItemIsTaggedEightWithTheLabelThenTheRawItem() {
        byte[] item = {10, 0, 0, 8, 0, 2, 'i', 'd', 0, 1, 'x', 0};
        byte[] encoded = PayloadCodec.encode(new Payload.VirtualItem("ec", item));
        assertArrayEquals(new byte[] {8, 2, 'e', 'c'}, PayloadCodec.encode(new Payload.VirtualItem("ec", new byte[0])));
        assertEquals(8, encoded[0]);
        assertEquals(4 + item.length, encoded.length, "not deflated");
        assertEquals(new Payload.VirtualItem("ec", item), PayloadCodec.decode(encoded));
        assertEquals(new Payload.VirtualItem("x".repeat(300), item),
                PayloadCodec.decode(PayloadCodec.encode(new Payload.VirtualItem("x".repeat(300), item))));
    }

    @Test
    public void rejectsCorruptInput() {
        assertThrows(CodecException.class, () -> PayloadCodec.decode(new byte[] {99}));
        assertThrows(CodecException.class, () -> PayloadCodec.decode(new byte[] {(byte) 0x81, 1, 2, 3}));
        assertThrows(CodecException.class, () -> PayloadCodec.decode(new byte[] {4, 5}));
        assertThrows(CodecException.class, () -> PayloadCodec.decode(new byte[0]));
    }
}
