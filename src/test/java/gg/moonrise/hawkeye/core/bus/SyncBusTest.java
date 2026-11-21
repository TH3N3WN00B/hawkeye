package gg.moonrise.hawkeye.core.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.util.redact.Redactor;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import org.junit.jupiter.api.Test;

public class SyncBusTest {

    @Test
    public void aMalformedUriWithAPasswordIsNotLogged() {
        String uri = "redis://alexander:hunter2 pw#x@cache.example:6379";
        // What Lettuce throws for it: URI.create quotes the whole input.
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> RedisClient.create(uri));
        assertTrue(failure.getMessage().contains("hunter2"), "the leak this guards against: " + failure.getMessage());
        String logged = SyncBus.failure(uri, failure) + " " + SyncBus.redact(uri);
        assertFalse(logged.contains("hunter2") || logged.contains("alexander"), logged);
        assertTrue(logged.contains(Redactor.MASK), logged);
        assertTrue(logged.contains("cache.example:6379"), "the host stays: " + logged);
    }

    @Test
    public void aPasswordOnlyUriIsScrubbedToo() {
        String uri = "redis://:s3cr3t%zz@cache.example";
        String message = SyncBus.failure(uri, new IllegalArgumentException("Malformed escape pair at index 13: " + uri));
        assertFalse(message.contains("s3cr3t"), message);
        assertEquals("redis://***@cache.example", SyncBus.redact(uri));
    }

    @Test
    public void aMessageWithoutTheUriIsKept() {
        assertEquals("Unable to connect to cache.example/10.0.0.1:6379", SyncBus.failure("redis://:pw@cache.example",
                new IllegalStateException("Unable to connect to cache.example/10.0.0.1:6379")));
        assertEquals("java.lang.IllegalStateException", SyncBus.failure("redis://cache.example",
                new IllegalStateException()));
        assertEquals("redis://cache.example:6379", SyncBus.redact("redis://cache.example:6379"));
        assertEquals("***@host", SyncBus.redact("user:pw@host"), "no scheme, still no user info");
    }

    @Test
    public void lettuceMasksCredentialsInItsOwnUriText() {
        RedisURI parsed = RedisURI.create("redis://alexander:hunter2@cache.example:6379");
        assertFalse(parsed.toString().contains("hunter2"), parsed.toString());
    }
}
