package gg.moonrise.hawkeye.config;

import de.exlll.configlib.NameFormatters;
import de.exlll.configlib.YamlConfigurationProperties;
import gg.moonrise.engine.config.Configuration;
import gg.moonrise.engine.state.Reloadable;
import gg.moonrise.moss.spring.SpringComponent;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

/**
 * Owns the {@code config.yml} snapshot. The file is read through the plugin-engine {@link Configuration}
 * (ConfigLib), which writes every default on first start and adds keys missing from an older file. A
 * reload publishes a complete snapshot or none: a file that fails to load keeps the previous one.
 */
@Slf4j
@SpringComponent
@RequiredArgsConstructor
public final class HawkeyeConfigProvider implements Reloadable {

    /** File name inside the plugin data folder. */
    public static final String FILE_NAME = "config.yml";

    private static final String HEADER = """
            Hawkeye configuration. Run /hawkeye reload after editing this file.
            """;

    private final @NotNull JavaPlugin plugin;
    private final AtomicReference<HawkeyeConfig> current = new AtomicReference<>();

    /** Reads the file at startup; a broken file leaves the defaults active until a reload succeeds. */
    @PostConstruct
    public void load() {
        if (reloadConfig() instanceof ReloadResult.Failed failed) {
            log.error("Using the default configuration: {}", failed.reason());
            current.compareAndSet(null, new HawkeyeConfig());
        }
    }

    /**
     * The active snapshot.
     *
     * @return the configuration last read successfully, or the defaults
     */
    public @NotNull HawkeyeConfig get() {
        return Objects.requireNonNull(current.get(), "the configuration is read in @PostConstruct");
    }

    /** Run by {@code PaperPlugin.reload()} for every {@link Reloadable} bean. */
    @Override
    public void reload() {
        if (reloadConfig() instanceof ReloadResult.Failed failed) {
            log.error("Keeping the previous configuration: {}", failed.reason());
        }
    }

    /**
     * Reads {@value #FILE_NAME} and, when it is valid, makes it the active snapshot.
     *
     * @return whether the file was read
     */
    public @NotNull ReloadResult reloadConfig() {
        Path file = plugin.getDataPath().resolve(FILE_NAME);
        try {
            Files.createDirectories(file.getParent());
            HawkeyeConfig loaded = Configuration.config(file.toFile(), HawkeyeConfig.class,
                    HawkeyeConfigProvider::properties).get();
            current.set(loaded);
            return new ReloadResult.Reloaded();
        } catch (IOException | RuntimeException e) {
            return new ReloadResult.Failed(FILE_NAME + ": " + Objects.requireNonNullElse(e.getMessage(),
                    e.getClass().getSimpleName()));
        }
    }

    private static @NotNull YamlConfigurationProperties.Builder<?> properties(
            @NotNull YamlConfigurationProperties.Builder<?> builder) {
        builder.setNameFormatter(NameFormatters.LOWER_KEBAB_CASE);
        builder.inputNulls(true);
        builder.outputNulls(false);
        builder.header(HEADER);
        return builder;
    }
}
