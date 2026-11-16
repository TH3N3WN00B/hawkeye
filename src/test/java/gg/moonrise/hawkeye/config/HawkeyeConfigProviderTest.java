package gg.moonrise.hawkeye.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.support.TestHawkeyePlugin;
import gg.moonrise.hawkeye.support.TestPlugins;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

public class HawkeyeConfigProviderTest {

    private TestHawkeyePlugin plugin;
    private HawkeyeConfigProvider provider;

    @BeforeEach
    public void setUp() {
        MockBukkit.mock();
        plugin = TestPlugins.load();
        provider = TestPlugins.bean(HawkeyeConfigProvider.class);
    }

    @AfterEach
    public void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    public void writesEveryKeyInKebabCase() throws IOException {
        String file = Files.readString(plugin.getDataPath().resolve(HawkeyeConfigProvider.FILE_NAME));
        assertTrue(file.contains("greetings-enabled: true"), file);
        assertTrue(file.contains("first-join:"), file);
        assertTrue(file.contains("reload-failed:"), file);
    }

    @Test
    public void reloadAppliesAValidFile() {
        TestPlugins.writeConfig(plugin, "greetings-enabled: false\n");
        assertInstanceOf(ReloadResult.Reloaded.class, provider.reloadConfig());
        assertFalse(provider.get().isGreetingsEnabled());
        assertEquals(new HawkeyeConfig().getMessages().getFirstJoin(), provider.get().getMessages().getFirstJoin(),
                "missing keys get their defaults");
    }

    @Test
    public void aBrokenFileKeepsThePreviousSnapshot() {
        HawkeyeConfig before = provider.get();
        TestPlugins.writeConfig(plugin, "greetings-enabled: [not, a, boolean]\n");
        assertInstanceOf(ReloadResult.Failed.class, provider.reloadConfig());
        assertEquals(before, provider.get());
    }
}
