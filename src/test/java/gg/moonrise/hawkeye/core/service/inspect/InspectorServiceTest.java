package gg.moonrise.hawkeye.core.service.inspect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.query.Area;
import gg.moonrise.hawkeye.entity.query.LookupQuery;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

public class InspectorServiceTest {

    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000a1e5");
    private static final LookupQuery BLOCK = LookupQuery.at(new Area.Block("world", 1, 2, 3));

    @Test
    public void theInspectorTogglesAndClears() {
        InspectorService inspector = new InspectorService(Clock.systemUTC());

        assertFalse(inspector.isInspecting(ALEX));
        assertTrue(inspector.toggle(ALEX));
        assertTrue(inspector.isInspecting(ALEX));
        assertFalse(inspector.toggle(ALEX));
        assertFalse(inspector.isInspecting(ALEX));

        inspector.toggle(ALEX);
        inspector.clear(ALEX);
        assertFalse(inspector.isInspecting(ALEX));
    }

    @Test
    public void theSameLookupRunsOnceASecond() {
        AtomicLong millis = new AtomicLong(Instant.parse("2026-11-16T12:00:00Z").toEpochMilli());
        InspectorService inspector = new InspectorService(new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return Instant.ofEpochMilli(millis.get());
            }
        });

        assertTrue(inspector.shouldLookUp(ALEX, BLOCK));
        millis.addAndGet(InspectorService.REPEAT_MILLIS - 1);
        assertFalse(inspector.shouldLookUp(ALEX, BLOCK));
        assertTrue(inspector.shouldLookUp(ALEX, LookupQuery.at(new Area.Block("world", 1, 3, 3))));
        assertTrue(inspector.shouldLookUp(UUID.randomUUID(), BLOCK));
        assertTrue(inspector.shouldLookUp(ALEX, BLOCK), "another lookup in between");
        millis.addAndGet(InspectorService.REPEAT_MILLIS);
        assertTrue(inspector.shouldLookUp(ALEX, BLOCK));
    }

    @Test
    public void clicksQueryTheirBlock() {
        ServerMock server = MockBukkit.mock();
        try {
            World world = server.addSimpleWorld("world");
            Block block = world.getBlockAt(4, 70, -9);
            InspectorService inspector = new InspectorService(Clock.systemUTC());

            assertEquals(LookupQuery.at(new Area.Block("world", 4, 70, -9)), inspector.history(block));
            LookupQuery transactions = inspector.transactions(block);
            assertEquals(new Area.Block("world", 4, 70, -9), transactions.area());
            assertEquals(Set.of(Action.CONTAINER_DEPOSIT, Action.CONTAINER_WITHDRAW), transactions.actions());
        } finally {
            MockBukkit.unmock();
        }
    }
}
