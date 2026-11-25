package gg.moonrise.hawkeye.core.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import gg.moonrise.hawkeye.config.env.Environment;
import gg.moonrise.hawkeye.config.file.StorageSettings;
import gg.moonrise.hawkeye.config.file.StorageType;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mariadb.jdbc.Configuration;
import org.mariadb.jdbc.export.SslMode;
import org.postgresql.PGProperty;

public class DatabaseFactoryTest {

    private static final String USER = "alexander-user";
    private static final String PASSWORD = "hunter2-password";
    private static final String KEY = "s3cret-key-value";

    private static final Environment ENVIRONMENT = name -> Optional.ofNullable(Map.of(
            StorageSettings.USERNAME_VARIABLE, USER, StorageSettings.PASSWORD_VARIABLE, PASSWORD,
            "SSL_KEY", KEY).get(name));

    @TempDir
    private Path folder;

    @ParameterizedTest
    @EnumSource(value = StorageType.class, names = {"MARIADB", "MYSQL", "POSTGRESQL"})
    public void hikariHoldsNoCredential(StorageType type) throws ReflectiveOperationException {
        StorageSettings settings = settings(type, Map.of("sslpassword", "${SSL_KEY}"));
        HikariConfig config = new DatabaseFactory(ENVIRONMENT).config("home", settings, folder);
        assertNull(config.getUsername());
        assertNull(config.getPassword());
        assertNull(config.getJdbcUrl());
        assertTrue(config.getDataSourceProperties().isEmpty());
        assertInstanceOf(RedactingDataSource.class, config.getDataSource());
        assertHidden(state(config));

        // The pool itself: built from that configuration, it connects in the background and fails (port 1).
        try (HikariDataSource pool = new HikariDataSource(config)) {
            assertNull(pool.getUsername());
            assertNull(pool.getPassword());
            assertNull(pool.getJdbcUrl());
            assertHidden(state(pool) + pool);
        }
    }

    @Test
    public void mariaDbGetsItsPropertiesAndConnectTimeout() throws Exception {
        Properties properties = properties(new DatabaseFactory(ENVIRONMENT).config("home",
                settings(StorageType.MARIADB, Map.of("sslMode", "verify-full", "keyPassword", "${SSL_KEY}")), folder));
        assertEquals(USER, properties.getProperty("user"));
        assertEquals(PASSWORD, properties.getProperty("password"));
        assertEquals("true", properties.getProperty("cachePrepStmts"));
        assertEquals("verify-full", properties.getProperty("sslMode"));
        assertEquals(KEY, properties.getProperty("keyPassword"));
        // Hikari's old login timeout: (10,000 ms + 500) / 1000 = 10 s; Connector/J takes milliseconds.
        assertEquals("10000", properties.getProperty("connectTimeout"));
        String url = "jdbc:mariadb://127.0.0.1:1/hawkeye";
        Configuration fromProperties = Configuration.parse(url, properties);
        Configuration fromUrl = Configuration.parse(url
                + "?sslMode=verify-full&connectTimeout=10000", new Properties());
        assertEquals(fromUrl.sslMode(), fromProperties.sslMode(), "a property is read like the URL parameter");
        assertEquals(10_000, fromProperties.connectTimeout());
        assertEquals(USER, fromProperties.user());

        Properties operator = properties(new DatabaseFactory(ENVIRONMENT).config("home",
                settings(StorageType.MARIADB, Map.of("connecttimeout", "2500")), folder));
        assertEquals("2500", operator.getProperty("connecttimeout"));
        assertNull(operator.getProperty("connectTimeout"), "the operator's own value wins, in any case");
    }

    @Test
    public void mariaDbConnectsWithoutTlsUnlessTheOperatorChooses() throws Exception {
        String url = "jdbc:mariadb://127.0.0.1:1/hawkeye";
        assertEquals(SslMode.VERIFY_FULL, Configuration.parse(url, new Properties()).sslMode(),
                "Connector/J 4's own default, which refuses a server without a CA-signed certificate");

        Properties defaults = properties(new DatabaseFactory(ENVIRONMENT).config("home",
                settings(StorageType.MYSQL, Map.of()), folder));
        assertEquals("disable", defaults.getProperty("sslMode"));
        assertEquals("true", defaults.getProperty("allowPublicKeyRetrieval"));
        Configuration plain = Configuration.parse(url, defaults);
        assertEquals(SslMode.DISABLE, plain.sslMode());
        assertTrue(plain.allowPublicKeyRetrieval(), "MySQL 8's caching_sha2_password over a plain connection");

        Properties legacy = properties(new DatabaseFactory(ENVIRONMENT).config("home",
                settings(StorageType.MARIADB, Map.of("useSSL", "true", "allowpublickeyretrieval", "false")), folder));
        assertNull(legacy.getProperty("sslMode"), "the legacy useSsl is the operator's choice, in any case");
        assertNull(legacy.getProperty("allowPublicKeyRetrieval"), "the operator's own value wins, in any case");
        Configuration encrypted = Configuration.parse(url, legacy);
        assertEquals(SslMode.VERIFY_FULL, encrypted.sslMode());
        assertFalse(encrypted.allowPublicKeyRetrieval());
    }

    @Test
    public void postgresGetsItsPropertiesAndLoginTimeout() throws Exception {
        Properties properties = properties(new DatabaseFactory(ENVIRONMENT).config("home",
                settings(StorageType.POSTGRESQL, Map.of("sslmode", "require", "sslpassword", "${SSL_KEY}")), folder));
        assertEquals(USER, properties.getProperty("user"));
        assertEquals(PASSWORD, properties.getProperty("password"));
        assertEquals(KEY, properties.getProperty("sslpassword"));
        assertEquals("10", properties.getProperty("loginTimeout"), "seconds");
        String url = "jdbc:postgresql://127.0.0.1:1/hawkeye";
        Properties fromProperties = org.postgresql.Driver.parseURL(url, properties);
        Properties fromUrl = org.postgresql.Driver.parseURL(url + "?sslmode=require&loginTimeout=10", new Properties());
        assertEquals(PGProperty.SSL_MODE.getOrDefault(fromUrl), PGProperty.SSL_MODE.getOrDefault(fromProperties));
        assertEquals("10", PGProperty.LOGIN_TIMEOUT.getOrDefault(fromProperties));
        assertEquals(USER, PGProperty.USER.getOrDefault(fromProperties));
    }

    @Test
    public void sqliteIsUnchanged() {
        HikariConfig config = new DatabaseFactory(name -> Optional.empty()).config("home",
                new StorageSettings().withSqliteFile("data/hawkeye.db"), folder);
        assertEquals("jdbc:sqlite:" + folder.resolve("data/hawkeye.db").toAbsolutePath(), config.getJdbcUrl());
        assertEquals("org.sqlite.JDBC", config.getDriverClassName());
        assertEquals("WAL", config.getDataSourceProperties().getProperty("journal_mode"));
        assertNull(config.getDataSource());
        assertEquals(4, config.getMaximumPoolSize());
    }

    private static StorageSettings settings(StorageType type, Map<String, String> parameters)
            throws ReflectiveOperationException {
        StorageSettings settings = StorageSettings.server(type, "127.0.0.1", 1, "hawkeye",
                "${" + StorageSettings.USERNAME_VARIABLE + "}", "${" + StorageSettings.PASSWORD_VARIABLE + "}",
                "hawkeye_");
        Field field = StorageSettings.class.getDeclaredField("parameters");
        field.setAccessible(true);
        field.set(settings, parameters);
        return settings;
    }

    private static Properties properties(HikariConfig config) throws ReflectiveOperationException {
        Field field = RedactingDataSource.class.getDeclaredField("properties");
        field.setAccessible(true);
        return (Properties) field.get(config.getDataSource());
    }

    /** Every field of the object and its superclasses, as text. */
    private static String state(Object target) throws IllegalAccessException {
        StringBuilder text = new StringBuilder();
        for (Class<?> type = target.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !field.trySetAccessible()) {
                    continue;
                }
                text.append(field.getName()).append('=').append(field.get(target)).append('\n');
            }
        }
        return text.toString();
    }

    private static void assertHidden(String text) {
        assertFalse(text.contains(USER), text);
        assertFalse(text.contains(PASSWORD), text);
        assertFalse(text.contains(KEY), text);
    }
}
