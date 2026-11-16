package gg.moonrise.hawkeye.util.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.entity.payload.Payload;
import gg.moonrise.hawkeye.entity.payload.SignSides;
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
                new Payload.EntityRef(UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e")));
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
    public void rejectsCorruptInput() {
        assertThrows(CodecException.class, () -> PayloadCodec.decode(new byte[] {99}));
        assertThrows(CodecException.class, () -> PayloadCodec.decode(new byte[] {(byte) 0x81, 1, 2, 3}));
        assertThrows(CodecException.class, () -> PayloadCodec.decode(new byte[] {4, 5}));
        assertThrows(CodecException.class, () -> PayloadCodec.decode(new byte[0]));
    }
}
