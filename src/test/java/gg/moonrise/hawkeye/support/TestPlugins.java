package gg.moonrise.hawkeye.support;

import gg.moonrise.hawkeye.command.registry.HawkeyeCommandRegistry;
import gg.moonrise.hawkeye.config.provider.HawkeyeConfigProvider;
import gg.moonrise.hawkeye.config.provider.ReloadResult;
import gg.moonrise.hawkeye.core.queue.event.EventWriter;
import gg.moonrise.hawkeye.core.storage.shard.StorageManager;
import gg.moonrise.hawkeye.core.storage.shard.StorageState;
import gg.moonrise.moss.paper.MossPaper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.command.ConsoleCommandSenderMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Loads {@link TestHawkeyePlugin} on a mocked server and gives tests its beans, a command manager and
 * the plain text of what a sender received.
 */
public final class TestPlugins {

    private TestPlugins() {
    }

    /**
     * Loads and enables the plugin; {@link MockBukkit#mock()} must have run.
     *
     * @return the enabled plugin
     */
    public static @NotNull TestHawkeyePlugin load() {
        return MockBukkit.load(TestHawkeyePlugin.class);
    }

    /**
     * Waits until the plugin's SQLite database is migrated and this server registered.
     *
     * @return the ready state
     */
    public static @NotNull StorageState.Ready awaitStorage() {
        try {
            return bean(StorageManager.class).ready().get(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("storage did not become ready", e);
        }
    }

    /** Writes every queued event and waits for it, so the database holds what was recorded. */
    public static void flush() {
        awaitStorage();
        try {
            bean(EventWriter.class).flush().get(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("flush failed", e);
        }
    }

    /** Reloads the configuration files and fails the test when they are invalid. */
    public static void reload() {
        if (bean(HawkeyeConfigProvider.class).reloadConfig() instanceof ReloadResult.Failed failed) {
            throw new IllegalStateException("reload failed: " + failed.reason());
        }
    }

    /**
     * A bean of the running plugin.
     *
     * @param type the bean type
     * @param <T>  the bean type
     * @return the bean
     */
    public static <T> @NotNull T bean(@NotNull Class<T> type) {
        return MossPaper.CONTEXT.getBean(type);
    }

    /**
     * Registers every command bean on a platform-free manager. Cloud's {@code PaperCommandManager}
     * cannot be built on MockBukkit, so the production registry logs that and the plugin still enables.
     *
     * @return the manager to run commands on
     */
    public static @NotNull TestCommandManager commands() {
        TestCommandManager manager = new TestCommandManager();
        bean(HawkeyeCommandRegistry.class).register(manager);
        return manager;
    }

    /**
     * Replaces the plugin's {@code config.yml}.
     *
     * @param plugin  the plugin
     * @param content the YAML to write
     */
    public static void writeConfig(@NotNull TestHawkeyePlugin plugin, @NotNull String content) {
        try {
            Path file = plugin.getDataPath().resolve("config.yml");
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The messages a sender received since the last call, as plain text.
     *
     * @param sender a {@link PlayerMock} or the console mock
     * @return one entry per message
     */
    public static @NotNull List<String> drain(@NotNull CommandSender sender) {
        List<String> lines = new ArrayList<>();
        while (true) {
            Component message = switch (sender) {
                case PlayerMock player -> player.nextComponentMessage();
                case ConsoleCommandSenderMock console -> console.nextComponentMessage();
                default -> throw new IllegalArgumentException("not a mock sender: " + sender);
            };
            if (message == null) {
                return lines;
            }
            lines.add(PlainTextComponentSerializer.plainText().serialize(message));
        }
    }
}
