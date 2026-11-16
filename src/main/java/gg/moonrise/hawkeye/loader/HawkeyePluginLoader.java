package gg.moonrise.hawkeye.loader;

import gg.moonrise.engine.paper.loader.PaperPluginLoader;
import java.util.List;
import org.jetbrains.annotations.NotNull;

/**
 * Paper plugin loader. The engine's {@link PaperPluginLoader} resolves plugin-engine's runtime
 * libraries (Spring, Moss, Cloud, ConfigLib) from Maven Central and the Moonrise repositories at
 * server start, so none of them is shaded into the plugin jar.
 *
 * <p>Add the plugin's own runtime libraries to {@link #libraries()} as {@code group:artifact:version};
 * an entry with the same {@code group:artifact} as an engine default replaces that default. Keep the
 * versions in step with {@code gradle/libs.versions.toml}.
 */
public final class HawkeyePluginLoader extends PaperPluginLoader {

    @Override
    public @NotNull List<String> libraries() {
        return List.of();
    }
}
