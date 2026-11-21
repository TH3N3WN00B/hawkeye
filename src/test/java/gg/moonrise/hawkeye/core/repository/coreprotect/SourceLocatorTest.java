package gg.moonrise.hawkeye.core.repository.coreprotect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.entity.importer.SourceLocation;
import gg.moonrise.hawkeye.entity.importer.SourceSettings;
import gg.moonrise.hawkeye.entity.importer.SourceType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class SourceLocatorTest {

    @TempDir
    private Path server;

    private Path coreProtect;
    private final SourceLocator locator = new SourceLocator();

    @BeforeEach
    public void setUp() throws IOException {
        coreProtect = Files.createDirectories(server.resolve("plugins/CoreProtect"));
    }

    @Test
    public void noArgumentReadsTheDefaultFolder() throws IOException {
        touch(coreProtect.resolve("database.db"));
        assertEquals(found(SourceSettings.ofFile(SourceType.SQLITE, coreProtect.resolve("database.db"))),
                locator.locate(null, coreProtect, server));
        assertEquals(locator.locate(null, coreProtect, server), locator.locate("  ", coreProtect, server));
    }

    @Test
    public void aFolderWithoutAConfigIsProbedForSqliteThenDuckDb() throws IOException {
        Path folder = Files.createDirectories(server.resolve("backup"));
        assertEquals(new SourceLocation.Missing(folder.toString()), locator.locate("backup", coreProtect, server));
        touch(folder.resolve("database.duckdb"));
        assertEquals(found(SourceSettings.ofFile(SourceType.DUCKDB, folder.resolve("database.duckdb"))),
                locator.locate("backup", coreProtect, server));
        touch(folder.resolve("database.db"));
        assertEquals(found(SourceSettings.ofFile(SourceType.SQLITE, folder.resolve("database.db"))),
                locator.locate("backup", coreProtect, server), "SQLite first");
    }

    @Test
    public void filesAreTakenByTheirExtension() throws IOException {
        Path duck = touch(server.resolve("history.DuckDB"));
        Path other = touch(server.resolve("copy.sqlite3"));
        assertEquals(found(SourceSettings.ofFile(SourceType.DUCKDB, duck)), locator.locate("history.DuckDB",
                coreProtect, server));
        assertEquals(found(SourceSettings.ofFile(SourceType.SQLITE, other)), locator.locate(other.toString(),
                coreProtect, server), "an absolute path");
        assertEquals(new SourceLocation.Missing(server.resolve("nothing.db").toString()),
                locator.locate("nothing.db", coreProtect, server));
    }

    @Test
    public void aSqliteConfigNamesTheFileInItsFolder() throws IOException {
        config(coreProtect, "database-type: sqlite\nsqlite-database: other.db\n");
        assertEquals(new SourceLocation.Missing(coreProtect.resolve("other.db").toString()),
                locator.locate(null, coreProtect, server));
        touch(coreProtect.resolve("other.db"));
        assertEquals(found(SourceSettings.ofFile(SourceType.SQLITE, coreProtect.resolve("other.db"))),
                locator.locate(null, coreProtect, server));
        config(coreProtect, "database-type: sqlite\nsqlite-database: ../escape.db\n");
        assertInstanceOf(SourceLocation.Invalid.class, locator.locate(null, coreProtect, server));
    }

    @Test
    public void aDuckDbConfigUsesItsFile() throws IOException {
        config(coreProtect, "database-type: duckdb\ntable-prefix: ignored_\n");
        touch(coreProtect.resolve("database.duckdb"));
        assertEquals(found(SourceSettings.ofFile(SourceType.DUCKDB, coreProtect.resolve("database.duckdb"))),
                locator.locate(null, coreProtect, server), "file engines always use co_");
    }

    @Test
    public void aMysqlConfigGivesItsServer() throws IOException {
        config(coreProtect, """
                database-type: MySQL
                table-prefix: cp_
                mysql-host: db.internal
                mysql-port: "3307"
                mysql-database: minecraft
                mysql-username: craft
                mysql-password: s3cret
                enable-ssl: true
                """);
        assertEquals(found(SourceSettings.ofServer(SourceType.MYSQL, "db.internal", 3307, "minecraft", "craft",
                "s3cret", "cp_", true)), locator.locate(null, coreProtect, server));
    }

    @Test
    public void aClickHouseConfigGivesItsServerWithDefaults() throws IOException {
        config(coreProtect, "database-type: clickhouse\nclickhouse-host: ch\nclickhouse-tls: true\n");
        assertEquals(found(SourceSettings.ofServer(SourceType.CLICKHOUSE, "ch", 8123, "default", "default", "", "co_",
                true)), locator.locate(null, coreProtect, server));
    }

    @Test
    public void withoutADatabaseTypeUseMysqlDecides() throws IOException {
        Path file = config(coreProtect, "use-mysql: true\nmysql-host: 10.0.0.2\n");
        assertEquals(found(SourceSettings.ofServer(SourceType.MYSQL, "10.0.0.2", 3306, "database", "root", "", "co_",
                false)), locator.locate(file.toString(), coreProtect, server), "a config file named directly");
        config(coreProtect, "use-mysql: false\n");
        touch(coreProtect.resolve("database.db"));
        assertEquals(found(SourceSettings.ofFile(SourceType.SQLITE, coreProtect.resolve("database.db"))),
                locator.locate(null, coreProtect, server));
    }

    @Test
    public void badConfigsAreInvalid() throws IOException {
        config(coreProtect, "database-type: oracle\n");
        assertTrue(reason().contains("oracle"), reason());
        config(coreProtect, "database-type: mysql\ntable-prefix: \"co_; DROP TABLE x\"\n");
        assertTrue(reason().contains("table-prefix"), reason());
        config(coreProtect, "database-type: mysql\nmysql-port: many\n");
        assertTrue(reason().contains("port"), reason());
        config(coreProtect, "database-type: [unclosed\n");
        assertTrue(reason().contains("cannot read"), reason());
        Path yaml = Files.writeString(server.resolve("other.yaml"), "database-type: clickhouse\nclickhouse-host: ''\n");
        assertInstanceOf(SourceLocation.Invalid.class, locator.locate(yaml.toString(), coreProtect, server));
    }

    @Test
    public void hostsAndDatabasesThatCouldAddUrlParametersAreInvalid() throws IOException {
        for (String host : new String[] {"db.internal?allowLoadLocalInfile=true", "db/other", "user@db", "db host",
                "db,other", "[::1]x", "db#frag"}) {
            config(coreProtect, "database-type: mysql\nmysql-host: '" + host + "'\n");
            assertTrue(reason().contains("host"), host + ": " + reason());
        }
        for (String database : new String[] {"minecraft?sslMode=disable", "mine/craft", "mine&x=1", "a b", "db;x",
                ""}) {
            config(coreProtect, "database-type: clickhouse\nclickhouse-host: ch\nclickhouse-database: '" + database
                    + "'\n");
            assertTrue(reason().contains("database"), database + ": " + reason());
        }
    }

    @Test
    public void hostNamesAndIpLiteralsAreAccepted() throws IOException {
        for (String host : new String[] {"db.internal", "mysql-1", "10.0.0.2", "::1", "[fe80::1]", "DB.Example.COM"}) {
            config(coreProtect, "database-type: mysql\nmysql-host: '" + host + "'\nmysql-database: mc_db-1$\n");
            assertEquals(found(SourceSettings.ofServer(SourceType.MYSQL, host, 3306, "mc_db-1$", "root", "", "co_",
                    false)), locator.locate(null, coreProtect, server), host);
        }
    }

    private String reason() {
        return assertInstanceOf(SourceLocation.Invalid.class, locator.locate(null, coreProtect, server)).reason();
    }

    private static SourceLocation found(SourceSettings settings) {
        return new SourceLocation.Found(settings);
    }

    private static Path config(Path folder, String yaml) throws IOException {
        return Files.writeString(folder.resolve("config.yml"), yaml);
    }

    private static Path touch(Path file) throws IOException {
        return Files.write(file, new byte[] {0});
    }
}
