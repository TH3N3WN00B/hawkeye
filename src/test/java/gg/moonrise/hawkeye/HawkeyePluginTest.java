package gg.moonrise.hawkeye;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.command.HawkeyeCommand;
import gg.moonrise.hawkeye.command.registry.HawkeyeCommandRegistry;
import gg.moonrise.hawkeye.config.provider.HawkeyeConfigProvider;
import gg.moonrise.hawkeye.core.queue.event.EventWriter;
import gg.moonrise.hawkeye.core.service.capture.EventRecorder;
import gg.moonrise.hawkeye.core.storage.shard.StorageManager;
import gg.moonrise.hawkeye.support.TestPlugins;
import gg.moonrise.moss.paper.MossPaper;
import java.nio.file.Files;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

public class HawkeyePluginTest {

    private ServerMock server;
    private HawkeyePlugin plugin;

    @BeforeEach
    public void setUp() {
        server = MockBukkit.mock();
        plugin = TestPlugins.load();
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void enablesWithEveryBean() {
        assertTrue(plugin.isEnabled());
        for (Class<?> bean : new Class<?>[] {HawkeyeConfigProvider.class, StorageManager.class, EventWriter.class,
                EventRecorder.class, HawkeyeCommand.class, HawkeyeCommandRegistry.class}) {
            assertNotNull(MossPaper.CONTEXT.getBean(bean), bean.getSimpleName());
        }
    }

    @Test
    public void writesTheDefaultFiles() {
        assertTrue(Files.isRegularFile(plugin.getDataPath().resolve(HawkeyeConfigProvider.CONFIG_FILE)));
        assertTrue(Files.isRegularFile(plugin.getDataPath().resolve(HawkeyeConfigProvider.MESSAGES_FILE)));
    }

    @Test
    public void disablesCleanly() {
        server.getPluginManager().disablePlugin(plugin);
        assertFalse(plugin.isEnabled());
        assertNull(MossPaper.CONTEXT, "Moss closes the context on disable");
    }
}
