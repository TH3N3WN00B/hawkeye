package gg.moonrise.hawkeye.core.repository.coreprotect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.HexFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.JDBC;

/** Driver resolution without a network: the repository is a {@code file:} folder. */
public class DriverResolverTest {

    private static final byte[] JAR = {'P', 'K', 3, 4, 'n', 'o', 't', ' ', 'a', ' ', 'd', 'r', 'i', 'v', 'e', 'r'};

    @TempDir
    private Path temp;

    private Path repository;
    private Path drivers;

    @BeforeEach
    public void setUp() throws IOException {
        repository = Files.createDirectories(temp.resolve("repo"));
        drivers = temp.resolve("plugins/Hawkeye/drivers");
    }

    @Test
    public void theArtifactsArePinned() {
        assertEquals("org/duckdb/duckdb_jdbc/1.4.5.0/duckdb_jdbc-1.4.5.0.jar", DriverArtifact.DUCKDB.repositoryPath());
        assertEquals("com/clickhouse/clickhouse-jdbc/0.10.0/clickhouse-jdbc-0.10.0-all.jar",
                DriverArtifact.CLICKHOUSE.repositoryPath());
        assertEquals(64, DriverArtifact.DUCKDB.sha256().length());
        assertEquals(64, DriverArtifact.CLICKHOUSE.sha256().length());
        assertEquals("org.duckdb.DuckDBDriver", DriverArtifact.DUCKDB.driverClass());
        assertEquals("com.clickhouse.jdbc.Driver", DriverArtifact.CLICKHOUSE.driverClass());
    }

    @Test
    public void aVisibleClassIsUsedWithoutAJar() throws SQLException {
        DriverArtifact sqlite = new DriverArtifact("org.xerial", "sqlite-jdbc", "1", "", "00", JDBC.class.getName());
        try (DriverResolver resolver = resolver()) {
            assertInstanceOf(JDBC.class, resolver.driver(sqlite));
            assertSame(resolver.driver(sqlite), resolver.driver(sqlite), "resolved once");
        }
        assertFalse(Files.exists(drivers), "nothing downloaded");
    }

    @Test
    public void aDownloadWithTheWrongChecksumIsDeletedAndRefused() throws IOException {
        DriverArtifact artifact = artifact("0".repeat(64));
        publish(artifact);
        try (DriverResolver resolver = resolver()) {
            SQLException failure = assertThrows(SQLException.class, () -> resolver.driver(artifact));
            assertTrue(failure.getMessage().contains(repository.toUri().resolve(artifact.repositoryPath()).toString()),
                    failure.getMessage());
            assertTrue(failure.getMessage().contains(drivers.toString()), failure.getMessage());
        }
        assertFalse(Files.exists(drivers.resolve(artifact.fileName())));
        assertFalse(Files.exists(drivers.resolve(artifact.fileName() + ".part")));
    }

    @Test
    public void aVerifiedDownloadIsKeptAndItsClassLoaded() throws IOException {
        DriverArtifact artifact = artifact(sha256(JAR));
        publish(artifact);
        try (DriverResolver resolver = resolver()) {
            SQLException failure = assertThrows(SQLException.class, () -> resolver.driver(artifact));
            assertTrue(failure.getMessage().contains("does not contain the driver"), failure.getMessage());
        }
        assertTrue(Files.isRegularFile(drivers.resolve(artifact.fileName())), "moved into the drivers folder");
    }

    @Test
    public void aJarInTheFolderIsCheckedToo() throws IOException {
        DriverArtifact artifact = artifact(sha256(JAR));
        Files.createDirectories(drivers);
        Files.write(drivers.resolve(artifact.fileName()), new byte[] {1, 2, 3});
        try (DriverResolver resolver = resolver()) {
            SQLException failure = assertThrows(SQLException.class, () -> resolver.driver(artifact));
            assertTrue(failure.getMessage().contains("expected " + artifact.sha256()), failure.getMessage());
        }
    }

    @Test
    public void aMissingArtifactNamesWhereToPutIt() {
        DriverArtifact artifact = artifact(sha256(JAR));
        try (DriverResolver resolver = resolver()) {
            SQLException failure = assertThrows(SQLException.class, () -> resolver.driver(artifact));
            assertTrue(failure.getMessage().contains(artifact.fileName() + " in " + drivers), failure.getMessage());
        }
    }

    @Test
    public void aParentThatFailsForUnknownClassesStillLetsTheJarLoad() throws IOException {
        DriverArtifact artifact = artifact(sha256(JAR));
        publish(artifact);
        ClassLoader failing = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) {
                throw new NullPointerException("No jar file selected");
            }
        };
        try (DriverResolver resolver = new DriverResolver(drivers, repository.toUri(), failing)) {
            SQLException failure = assertThrows(SQLException.class, () -> resolver.driver(artifact));
            assertTrue(failure.getMessage().contains("does not contain the driver"), failure.getMessage());
        }
        assertTrue(Files.isRegularFile(drivers.resolve(artifact.fileName())), "the jar was used");
    }

    private DriverResolver resolver() {
        return new DriverResolver(drivers, repository.toUri(), getClass().getClassLoader());
    }

    private static DriverArtifact artifact(String sha256) {
        return new DriverArtifact("com.example", "fake-jdbc", "1.0", "all", sha256, "com.example.FakeDriver");
    }

    private void publish(DriverArtifact artifact) throws IOException {
        Path file = repository.resolve(artifact.repositoryPath());
        Files.createDirectories(file.getParent());
        Files.write(file, JAR);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
