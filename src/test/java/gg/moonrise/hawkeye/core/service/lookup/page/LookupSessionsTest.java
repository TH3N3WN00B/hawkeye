package gg.moonrise.hawkeye.core.service.lookup.page;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.config.provider.HawkeyeConfigProvider;
import gg.moonrise.hawkeye.core.service.lookup.LookupTotal;
import gg.moonrise.hawkeye.entity.query.Area;
import gg.moonrise.hawkeye.entity.query.LookupQuery;
import gg.moonrise.hawkeye.support.TestPlugins;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

public class LookupSessionsTest {

    private static final LookupSession SESSION = new LookupSession(LookupQuery.at(new Area.Everywhere()), 8,
            new LookupTotal.Exact(20),
            List.of(0L));

    private ServerMock server;
    private MutableClock clock;
    private LookupSessions sessions;

    @BeforeEach
    public void setUp() {
        server = MockBukkit.mock();
        TestPlugins.load();
        clock = new MutableClock(Instant.parse("2026-11-16T12:00:00Z"));
        sessions = new LookupSessions(TestPlugins.bean(HawkeyeConfigProvider.class), clock);
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void eachSenderHasTheirOwnSession() {
        PlayerMock alex = server.addPlayer("Alex");
        PlayerMock sam = server.addPlayer("Sam");
        sessions.put(alex, SESSION);

        assertEquals(Optional.of(SESSION), sessions.get(alex));
        assertTrue(sessions.get(sam).isEmpty());
        assertTrue(sessions.get(server.getConsoleSender()).isEmpty());
        assertEquals(alex.getUniqueId().toString(), LookupSessions.key(alex));
        assertEquals("#console", LookupSessions.key(server.getConsoleSender()));
    }

    @Test
    public void aSessionExpiresAfterTheConfiguredMinutesWithoutUse() {
        PlayerMock alex = server.addPlayer("Alex");
        sessions.put(alex, SESSION);

        clock.advance(Duration.ofMinutes(10));
        assertTrue(sessions.get(alex).isPresent());
        clock.advance(Duration.ofMinutes(10));
        assertTrue(sessions.get(alex).isPresent(), "using a session keeps it");
        clock.advance(Duration.ofMinutes(16));
        assertTrue(sessions.get(alex).isEmpty());
    }

    @Test
    public void anUpdateNeverReplacesANewerSession() {
        PlayerMock alex = server.addPlayer("Alex");
        sessions.put(alex, SESSION);
        LookupSession newer = new LookupSession(LookupQuery.at(new Area.World("world")), 8, new LookupTotal.Exact(3),
                List.of(0L));
        sessions.put(alex, newer);

        assertFalse(sessions.replace(alex, SESSION, SESSION.withStart(2, 40L)));
        assertEquals(Optional.of(newer), sessions.get(alex));
        assertTrue(sessions.replace(alex, newer, newer.withStart(2, 2L)));
        assertEquals(2, sessions.get(alex).orElseThrow().knownPages());
    }

    @Test
    public void clearingForgetsTheSession() {
        sessions.put(server.getConsoleSender(), SESSION);
        sessions.clear(server.getConsoleSender());
        assertTrue(sessions.get(server.getConsoleSender()).isEmpty());
    }

    /** A clock tests move by hand. */
    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(@NotNull Instant now) {
            this.now = now;
        }

        private void advance(@NotNull Duration duration) {
            now = now.plus(duration);
        }

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
            return now;
        }
    }
}
