package gg.moonrise.hawkeye.config.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.config.env.Environment;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

public class NetworkSettingsTest {

    private static final Environment NONE = name -> Optional.empty();

    private static final Environment SET = name -> Optional.ofNullable(Map.of(
            "HAWKEYE_REDIS_PASSWORD", "r3d1s",
            "NOT_A_URI", "http://nope").get(name));

    @Test
    public void theDefaultUriNeedsNoVariable() throws ReflectiveOperationException {
        NetworkSettings settings = redis("redis://localhost:6379");
        assertEquals(List.of(), settings.problems(NONE));
        assertEquals("redis://localhost:6379", settings.redisUri(NONE));
        assertEquals(List.of(), new NetworkSettings().problems(NONE));
    }

    @Test
    public void aReferenceIsResolvedWhereTheBusConnects() throws ReflectiveOperationException {
        NetworkSettings settings = redis("redis://:${HAWKEYE_REDIS_PASSWORD}@localhost:6379");
        assertEquals(List.of(), settings.problems(SET));
        assertEquals("redis://:r3d1s@localhost:6379", settings.redisUri(SET));
    }

    @Test
    public void anUnsetVariableIsAProblemNamingIt() throws ReflectiveOperationException {
        NetworkSettings settings = redis("redis://:${HAWKEYE_REDIS_PASSWORD}@localhost:6379");
        assertEquals(List.of("network.redis-uri: the environment variable HAWKEYE_REDIS_PASSWORD is not set"),
                settings.problems(NONE));
        assertThrows(IllegalStateException.class, () -> settings.redisUri(NONE));
    }

    @Test
    public void theResolvedValueMustBeARedisUriAndIsNeverQuoted() throws ReflectiveOperationException {
        assertEquals(List.of("network.redis-uri must be a redis:// or rediss:// URI"),
                redis("${NOT_A_URI}").problems(SET));
        List<String> problems = redis("http://user:hunter2@host").problems(NONE);
        assertEquals(List.of("network.redis-uri must be a redis:// or rediss:// URI"), problems);
        NetworkSettings polling = redis("${UNSET_ONE}");
        set(polling, "bus", NetworkSettings.BusType.NONE);
        assertEquals(List.of(), polling.problems(NONE), "no Redis, nothing to resolve");
    }

    @Test
    public void toStringShowsNoUri() throws ReflectiveOperationException {
        String text = redis("redis://:hunter2@cache.example:6379").toString();
        assertFalse(text.contains("hunter2") || text.contains("cache.example"), text);
        assertTrue(text.contains("REDIS"), text);
    }

    private static NetworkSettings redis(String uri) throws ReflectiveOperationException {
        NetworkSettings settings = new NetworkSettings();
        set(settings, "bus", NetworkSettings.BusType.REDIS);
        set(settings, "redisUri", uri);
        return settings;
    }

    private static void set(Object target, String name, Object value) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
