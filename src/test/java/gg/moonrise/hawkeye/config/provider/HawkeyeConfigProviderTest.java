package gg.moonrise.hawkeye.config.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.config.file.HawkeyeConfig;
import gg.moonrise.hawkeye.config.file.LogCategory;
import gg.moonrise.hawkeye.config.file.StorageType;
import gg.moonrise.hawkeye.support.TestHawkeyePlugin;
import gg.moonrise.hawkeye.support.TestPlugins;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
        String config = Files.readString(plugin.getDataPath().resolve(HawkeyeConfigProvider.CONFIG_FILE));
        assertTrue(config.contains("flush-interval-millis: 500"), config);
        assertTrue(config.contains("table-prefix: hawkeye_"), config);
        assertTrue(config.contains("redacted-commands:"), config);
        String messages = Files.readString(plugin.getDataPath().resolve(HawkeyeConfigProvider.MESSAGES_FILE));
        assertTrue(messages.contains("block-break:"), messages);
        assertTrue(messages.contains("storage-not-ready:"), messages);
    }

    @Test
    public void reloadAppliesAValidFileAndKeepsDefaultsForMissingKeys() {
        TestPlugins.writeConfig(plugin, "logging:\n  chat: false\nstorage:\n  type: POSTGRESQL\n  host: db\n");
        assertInstanceOf(ReloadResult.Reloaded.class, provider.reloadConfig());
        assertFalse(provider.get().logging().enabled(LogCategory.CHAT));
        assertTrue(provider.get().logging().enabled(LogCategory.BLOCK_BREAK), "missing keys get their defaults");
        assertEquals(StorageType.POSTGRESQL, provider.get().storage().type());
        assertEquals(5432, provider.get().storage().effectivePort());
        assertEquals(new HawkeyeConfig().writer(), provider.get().writer());
    }

    @Test
    public void anInvalidFileKeepsThePreviousSnapshot() {
        HawkeyeConfig before = provider.get();
        TestPlugins.writeConfig(plugin, "writer:\n  batch-size: 0\n");
        ReloadResult.Failed failed = assertInstanceOf(ReloadResult.Failed.class, provider.reloadConfig());
        assertTrue(failed.reason().contains("writer.batch-size"), failed.reason());
        assertSame(before, provider.get());
    }

    @Test
    public void aBrokenFileKeepsThePreviousSnapshot() {
        HawkeyeConfig before = provider.get();
        TestPlugins.writeConfig(plugin, "writer: [not, a, section]\n");
        assertInstanceOf(ReloadResult.Failed.class, provider.reloadConfig());
        assertSame(before, provider.get());
    }

    @Test
    public void aFileWithTheRemovedBatchEventsKeyStillLoads() throws IOException {
        TestPlugins.writeConfig(plugin, "archive:\n  enabled: true\n  after-hours: 12\n  interval-minutes: 30\n"
                + "  batch-events: 50000\n");
        assertInstanceOf(ReloadResult.Reloaded.class, provider.reloadConfig());
        assertEquals(12, provider.get().archive().afterHours());
        assertEquals(30, provider.get().archive().intervalMinutes());
        assertEquals(262_144, provider.get().archive().segmentEvents(), "a key missing from the file gets its default");
        assertEquals(60, provider.get().archive().settleSeconds());
        // The reload writes the file back with the current keys: the removed one is dropped, the new ones added.
        String written = Files.readString(plugin.getDataPath().resolve(HawkeyeConfigProvider.CONFIG_FILE));
        assertFalse(written.contains("batch-events"), written);
        assertTrue(written.contains("segment-events: 262144") && written.contains("settle-seconds: 60"), written);
        assertTrue(written.contains("after-hours: 12"), written);
    }

    @Test
    public void aMessagesFileWithoutTheLowerBoundKeyGetsItsDefault() throws IOException {
        Path messages = plugin.getDataPath().resolve(HawkeyeConfigProvider.MESSAGES_FILE);
        Files.writeString(messages, "lookup:\n  unknown-total: '<gray>?'\n");
        assertInstanceOf(ReloadResult.Reloaded.class, provider.reloadConfig());
        assertEquals("<gray>?", provider.messages().getLookup().getUnknownTotal());
        assertEquals("<value>+", provider.messages().getLookup().getAtLeast(), "a key missing from the file");
        String written = Files.readString(messages);
        assertTrue(written.contains("at-least: <value>+") || written.contains("at-least: '<value>+'"), written);
        assertTrue(written.contains("<gray>?"), written);
    }

    @Test
    public void archiveSettingsAreValidated() {
        TestPlugins.writeConfig(plugin, "archive:\n  segment-events: 1000\n  settle-seconds: 2\n");
        ReloadResult.Failed failed = assertInstanceOf(ReloadResult.Failed.class, provider.reloadConfig());
        assertTrue(failed.reason().contains("archive.segment-events"), failed.reason());
        assertTrue(failed.reason().contains("archive.settle-seconds"), failed.reason());
    }

    @Test
    public void defaultsAreValid() {
        assertEquals(List.of(), new HawkeyeConfig().problems());
    }
}
