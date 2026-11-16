package gg.moonrise.hawkeye.loader;

import gg.moonrise.engine.paper.loader.PaperPluginLoader;
import java.util.List;
import org.jetbrains.annotations.NotNull;

/**
 * Paper plugin loader. The engine's {@link PaperPluginLoader} resolves plugin-engine's runtime
 * libraries (Spring, Moss, Cloud, ConfigLib) from Maven Central and the Moonrise repositories at
 * server start; {@link #libraries()} adds Hawkeye's own (the pool, the three JDBC drivers, the cache
 * and the Redis client), so none of them is shaded into the plugin jar.
 *
 * <p>Keep these coordinates in step with {@code gradle/libs.versions.toml}:
 * {@code HawkeyePluginLoaderTest} fails the build when they drift.
 */
public final class HawkeyePluginLoader extends PaperPluginLoader {

    /** Hawkeye's runtime libraries, {@code group:artifact:version}. */
    public static final List<String> LIBRARIES = List.of(
            "com.zaxxer:HikariCP:7.0.2",
            "org.xerial:sqlite-jdbc:3.50.3.0",
            "org.mariadb.jdbc:mariadb-java-client:3.5.1",
            "org.postgresql:postgresql:42.7.13",
            "com.github.ben-manes.caffeine:caffeine:3.2.3",
            "io.lettuce:lettuce-core:6.5.1.RELEASE");

    @Override
    public @NotNull List<String> libraries() {
        return LIBRARIES;
    }
}
