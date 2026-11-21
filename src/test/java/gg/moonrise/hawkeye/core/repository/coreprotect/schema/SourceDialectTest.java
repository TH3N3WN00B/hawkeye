package gg.moonrise.hawkeye.core.repository.coreprotect.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.entity.importer.source.SourceSettings;
import gg.moonrise.hawkeye.entity.importer.source.SourceType;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;

public class SourceDialectTest {

    private static final SourceSettings MYSQL = SourceSettings.ofServer(SourceType.MYSQL, "db", 3306, "mc", "root",
            "pw", "cp_", true);
    private static final SourceSettings CLICKHOUSE = SourceSettings.ofServer(SourceType.CLICKHOUSE, "::1", 8443,
            "default", "default", "pw", "co_", true);

    @Test
    public void urlsAndQuotingPerEngine() {
        assertEquals("jdbc:mariadb://db:3306/mc", SourceDialect.MYSQL.url(MYSQL));
        assertEquals("jdbc:clickhouse:https://[::1]:8443/default", SourceDialect.CLICKHOUSE.url(CLICKHOUSE));
        Path file = Path.of("/srv/plugins/CoreProtect/database.duckdb");
        assertEquals("jdbc:duckdb:" + file.toAbsolutePath(), SourceDialect.DUCKDB.url(SourceSettings.ofFile(
                SourceType.DUCKDB, file)));
        assertEquals("`cp_user`", SourceDialect.MYSQL.table(MYSQL, "user"));
        assertEquals("\"co_user\"", SourceDialect.SQLITE.quote("co_user"));
        assertThrows(IllegalArgumentException.class, () -> SourceDialect.MYSQL.quote("a`b"));
        assertEquals("rowid", SourceDialect.DUCKDB.order().key());
        assertEquals("time", SourceDialect.CLICKHOUSE.order().key());
    }

    @Test
    public void propertiesOpenReadOnlyAndCarryTheCredentials() {
        Properties mysql = SourceDialect.MYSQL.properties(MYSQL);
        assertEquals("root", mysql.getProperty("user"));
        assertEquals("trust", mysql.getProperty("sslMode"));
        assertEquals("true", mysql.getProperty("allowPublicKeyRetrieval"));
        assertEquals("true", SourceDialect.DUCKDB.properties(SourceSettings.ofFile(SourceType.DUCKDB,
                Path.of("x.duckdb"))).getProperty("duckdb.read_only"));
        Properties clickhouse = SourceDialect.CLICKHOUSE.properties(CLICKHOUSE);
        assertEquals("true", clickhouse.getProperty("binary_string_support"));
        assertEquals("true", clickhouse.getProperty("ssl"));
        assertEquals("LZ4", clickhouse.getProperty("clickhouse_setting_network_compression_method"));
        int mode = Integer.parseInt(SourceDialect.SQLITE.properties(SourceSettings.ofFile(SourceType.SQLITE,
                Path.of("x.db"))).getProperty("open_mode"));
        assertEquals(1, mode & 1, "SQLITE_OPEN_READONLY");
        assertEquals(0, mode & 6, "neither SQLITE_OPEN_READWRITE nor SQLITE_OPEN_CREATE");
    }

    @Test
    public void prefixesAreCoreProtectIdentifiers() {
        assertTrue(SourceDialect.validPrefix("co_"));
        assertTrue(SourceDialect.validPrefix("_Server2_"));
        assertFalse(SourceDialect.validPrefix(""));
        assertFalse(SourceDialect.validPrefix("2co"));
        assertFalse(SourceDialect.validPrefix("co-"));
        assertFalse(SourceDialect.validPrefix("co_`"));
    }
}
