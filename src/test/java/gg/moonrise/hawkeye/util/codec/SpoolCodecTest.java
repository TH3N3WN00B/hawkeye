package gg.moonrise.hawkeye.util.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.hawkeye.entity.event.PendingEvent;
import gg.moonrise.hawkeye.util.codec.binary.CodecException;
import gg.moonrise.hawkeye.util.position.BlockPositions;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

public class SpoolCodecTest {

    @Test
    public void roundTripsEventsWithAndWithoutOptionalParts() {
        PendingEvent full = new PendingEvent(123_456L, "world_nether", BlockPositions.pack(-5, 70, 9),
                Actor.player(UUID.randomUUID(), "Alex"), Action.BLOCK_BREAK, "minecraft:chest[facing=north]",
                "minecraft:air", 0, new byte[] {1, 2, 3});
        PendingEvent bare = new PendingEvent(7L, "world", 0L, Actor.source("tnt"), Action.CONTAINER_DEPOSIT,
                "minecraft:dirt", null, 64, null);
        List<PendingEvent> decoded = SpoolCodec.decode(SpoolCodec.encode(List.of(full, bare)));
        assertEquals(2, decoded.size());
        PendingEvent first = decoded.getFirst();
        assertEquals(full.stamp(), first.stamp());
        assertEquals(full.world(), first.world());
        assertEquals(full.position(), first.position());
        assertEquals(full.actor(), first.actor());
        assertEquals(full.action(), first.action());
        assertEquals(full.subject(), first.subject());
        assertEquals(full.secondary(), first.secondary());
        assertArrayEquals(full.data(), first.data());
        PendingEvent second = decoded.get(1);
        assertNull(second.secondary());
        assertNull(second.data());
        assertEquals(64, second.amount());
    }

    @Test
    public void rejectsOtherFiles() {
        assertThrows(CodecException.class, () -> SpoolCodec.decode("hello".getBytes()));
    }
}
