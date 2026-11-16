package gg.moonrise.hawkeye.core.service.lookup;

import gg.moonrise.hawkeye.core.service.capture.EventRecorder;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.hawkeye.support.TestHawkeyePlugin;
import gg.moonrise.hawkeye.support.TestPlugins;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import org.mockbukkit.mockbukkit.ServerMock;

/** Shared set-up for the lookup tests that run on a mocked server. */
public final class LookupFixtures {

    /** The data folder MockBukkit gives {@link TestHawkeyePlugin} (its name and synthetic version). */
    private static final String DATA_FOLDER = "TestHawkeyePlugin-0.0.0";

    private LookupFixtures() {
    }

    /**
     * Writes {@code config.yml} before the plugin loads, for settings that only apply at start (storage,
     * shards), then loads it.
     *
     * @param server the mocked server
     * @param config the YAML
     * @return the enabled plugin
     */
    public static @NotNull TestHawkeyePlugin loadWith(@NotNull ServerMock server, @NotNull String config) {
        try {
            File folder = server.getPluginManager().createTemporaryDirectory(DATA_FOLDER);
            Files.writeString(folder.toPath().resolve("config.yml"), config);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        TestHawkeyePlugin plugin = TestPlugins.load();
        if (!plugin.getDataPath().endsWith(DATA_FOLDER)) {
            throw new IllegalStateException("MockBukkit moved the data folder to " + plugin.getDataPath());
        }
        return plugin;
    }

    /**
     * Records {@code count} block breaks at x = 0 .. count - 1, oldest first.
     *
     * @param actor who broke them
     * @param world the world
     * @param count how many
     */
    public static void breaks(@NotNull Actor actor, @NotNull World world, int count) {
        EventRecorder recorder = TestPlugins.bean(EventRecorder.class);
        for (int x = 0; x < count; x++) {
            recorder.record(Action.BLOCK_BREAK, actor, world, x, 64, 0, "minecraft:stone", EventRecorder.AIR, 0,
                    null);
        }
    }
}
