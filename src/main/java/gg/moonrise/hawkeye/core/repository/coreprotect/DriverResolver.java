package gg.moonrise.hawkeye.core.repository.coreprotect;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

/**
 * Finds the {@link Driver} of a {@link DriverArtifact}: the class when the parent class loader already
 * sees it; else the jar in the drivers folder; else a download from the repository into that folder
 * (a temporary file, checked against the pinned SHA-256, then moved into place). A jar is always
 * checked against the checksum before it is loaded, and loaded in its own class loader whose parent is
 * the plugin's. The driver is instantiated directly and never registered with {@code DriverManager}.
 * A parent that fails with a runtime exception for a class it does not have (MockBukkit's plugin class
 * loader throws {@code NullPointerException}) counts as not having it, like one that throws
 * {@link ClassNotFoundException}. Thread-safe; {@link #close()} closes the class loaders.
 */
@Slf4j
public final class DriverResolver implements AutoCloseable {

    private static final int TIMEOUT_MILLIS = 30_000;

    private final @NotNull Path folder;
    private final @NotNull URI repository;
    private final @NotNull ClassLoader parent;
    private final Map<DriverArtifact, Driver> drivers = new ConcurrentHashMap<>();
    private final List<URLClassLoader> loaders = new ArrayList<>();

    /**
     * A resolver.
     *
     * @param folder     where jars are kept ({@code <plugin data folder>/drivers})
     * @param repository the Maven repository root to download from, ending with {@code /}
     * @param parent     the class loader the driver classes see (the plugin's)
     */
    public DriverResolver(@NotNull Path folder, @NotNull URI repository, @NotNull ClassLoader parent) {
        this.folder = folder;
        this.repository = repository;
        this.parent = new NotFoundOnFailure(parent);
    }

    /**
     * The driver of an artifact, resolved once and kept.
     *
     * @param artifact the artifact
     * @return the driver
     * @throws SQLException when the jar cannot be found, downloaded, verified or loaded; the message
     *                      names the URL and the folder, so an operator can place the jar by hand
     */
    public synchronized @NotNull Driver driver(@NotNull DriverArtifact artifact) throws SQLException {
        Driver known = drivers.get(artifact);
        if (known != null) {
            return known;
        }
        Driver driver = resolve(artifact);
        drivers.put(artifact, driver);
        return driver;
    }

    @Override
    public synchronized void close() {
        for (URLClassLoader loader : loaders) {
            try {
                loader.close();
            } catch (IOException e) {
                log.warn("Could not close the class loader of {}", loader, e);
            }
        }
        loaders.clear();
        drivers.clear();
    }

    private @NotNull Driver resolve(@NotNull DriverArtifact artifact) throws SQLException {
        try {
            return instantiate(Class.forName(artifact.driverClass(), true, parent), artifact);
        } catch (ClassNotFoundException e) {
            // Not bundled: use the jar.
        }
        Path jar = folder.resolve(artifact.fileName());
        if (!Files.isRegularFile(jar)) {
            download(artifact, jar);
        }
        verify(artifact, jar);
        return load(artifact, jar);
    }

    private void download(@NotNull DriverArtifact artifact, @NotNull Path jar) throws SQLException {
        URI source = repository.resolve(artifact.repositoryPath());
        log.info("Downloading the {} driver from {} into {}", artifact.artifact(), source, folder);
        Path partial = folder.resolve(artifact.fileName() + ".part");
        try {
            Files.createDirectories(folder);
            URLConnection connection = source.toURL().openConnection();
            connection.setConnectTimeout(TIMEOUT_MILLIS);
            connection.setReadTimeout(TIMEOUT_MILLIS);
            String sha256;
            try (InputStream in = new DigestInputStream(connection.getInputStream(), sha256());
                    OutputStream out = Files.newOutputStream(partial)) {
                in.transferTo(out);
                sha256 = HexFormat.of().formatHex(((DigestInputStream) in).getMessageDigest().digest());
            }
            if (!sha256.equals(artifact.sha256())) {
                Files.deleteIfExists(partial);
                throw mismatch(artifact, source, sha256, folder);
            }
            move(partial, jar);
        } catch (IOException e) {
            deleteQuietly(partial);
            throw new SQLException("Could not download the " + artifact.artifact() + " driver from " + source + " ("
                    + e.getMessage() + "); place " + artifact.fileName() + " in " + folder + " by hand", e);
        }
    }

    private void verify(@NotNull DriverArtifact artifact, @NotNull Path jar) throws SQLException {
        String sha256;
        try (InputStream in = new DigestInputStream(Files.newInputStream(jar), sha256())) {
            in.transferTo(OutputStream.nullOutputStream());
            sha256 = HexFormat.of().formatHex(((DigestInputStream) in).getMessageDigest().digest());
        } catch (IOException e) {
            throw new SQLException("Could not read " + jar + ": " + e.getMessage(), e);
        }
        if (!sha256.equals(artifact.sha256())) {
            throw new SQLException("The " + artifact.artifact() + " driver at " + jar + " has SHA-256 " + sha256
                    + ", expected " + artifact.sha256() + "; replace it with "
                    + repository.resolve(artifact.repositoryPath()));
        }
    }

    private @NotNull Driver load(@NotNull DriverArtifact artifact, @NotNull Path jar) throws SQLException {
        URLClassLoader loader;
        try {
            loader = new URLClassLoader("hawkeye-" + artifact.artifact(), new URL[] {jar.toUri().toURL()}, parent);
        } catch (MalformedURLException e) {
            throw new SQLException("Cannot load " + jar, e);
        }
        loaders.add(loader);
        try {
            return instantiate(Class.forName(artifact.driverClass(), true, loader), artifact);
        } catch (ClassNotFoundException | LinkageError e) {
            throw new SQLException(jar + " does not contain the driver " + artifact.driverClass(), e);
        }
    }

    private static @NotNull Driver instantiate(@NotNull Class<?> type, @NotNull DriverArtifact artifact)
            throws SQLException {
        try {
            return (Driver) type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | ClassCastException e) {
            Throwable cause = e instanceof InvocationTargetException invocation && invocation.getCause() != null
                    ? invocation.getCause() : e;
            throw new SQLException("Cannot create the driver " + artifact.driverClass() + ": " + cause, cause);
        }
    }

    private static @NotNull SQLException mismatch(@NotNull DriverArtifact artifact, @NotNull URI source,
            @NotNull String sha256, @NotNull Path folder) {
        return new SQLException("The " + artifact.artifact() + " driver downloaded from " + source + " has SHA-256 "
                + sha256 + ", expected " + artifact.sha256() + "; it was deleted. Place " + artifact.fileName()
                + " in " + folder + " by hand");
    }

    private static void move(@NotNull Path from, @NotNull Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteQuietly(@NotNull Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.debug("Could not delete {}", file, e);
        }
    }

    /** Delegates to a class loader, reporting any failure to load a class as {@link ClassNotFoundException}. */
    private static final class NotFoundOnFailure extends ClassLoader {

        private NotFoundOnFailure(@NotNull ClassLoader parent) {
            super(parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            try {
                return super.loadClass(name, resolve);
            } catch (RuntimeException e) {
                throw new ClassNotFoundException(name, e);
            }
        }
    }

    private static @NotNull MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java runtime has SHA-256", e);
        }
    }
}
