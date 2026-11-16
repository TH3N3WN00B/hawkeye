package gg.moonrise.hawkeye.core.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import gg.moonrise.hawkeye.config.file.StorageType;
import java.util.List;
import org.junit.jupiter.api.Test;

public class DialectTest {

    @Test
    public void mapsStorageTypes() {
        assertEquals(Dialect.SQLITE, Dialect.of(StorageType.SQLITE));
        assertEquals(Dialect.MYSQL, Dialect.of(StorageType.MARIADB));
        assertEquals(Dialect.MYSQL, Dialect.of(StorageType.MYSQL));
        assertEquals(Dialect.POSTGRESQL, Dialect.of(StorageType.POSTGRESQL));
    }

    @Test
    public void ignoresDuplicatesPerEngine() {
        assertEquals("INSERT INTO t (a, b) VALUES (?, ?), (?, ?) ON CONFLICT DO NOTHING",
                Dialect.POSTGRESQL.insertIgnoringDuplicates("t", List.of("a", "b"), 2));
        assertEquals("INSERT INTO t (a) VALUES (?) ON DUPLICATE KEY UPDATE a = a",
                Dialect.MYSQL.insertIgnoringDuplicates("t", List.of("a"), 1));
    }

    @Test
    public void upsertsPerEngine() {
        assertEquals("INSERT INTO t (k, v) VALUES (?, ?) ON CONFLICT (k) DO UPDATE SET v = excluded.v",
                Dialect.SQLITE.upsert("t", List.of("k", "v"), "k", List.of("v"), 1));
        assertEquals("INSERT INTO t (k, v) VALUES (?, ?) ON DUPLICATE KEY UPDATE v = VALUES(v)",
                Dialect.MYSQL.upsert("t", List.of("k", "v"), "k", List.of("v"), 1));
    }
}
