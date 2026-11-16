package gg.moonrise.hawkeye;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.command.HawkeyeCommand;
import gg.moonrise.hawkeye.loader.HawkeyePluginLoader;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

public class PaperPluginYmlTest {

    @Test
    public void declaresTheEntryPoints() throws IOException {
        Map<String, Object> yml = load();
        assertEquals(HawkeyePlugin.class.getName(), yml.get("main"));
        assertEquals(HawkeyePluginLoader.class.getName(), yml.get("loader"));
        assertEquals("26.3", yml.get("api-version"));
        assertEquals(true, yml.get("folia-supported"));
        assertFalse(String.valueOf(yml.get("version")).contains("${"), "version is expanded");
    }

    @Test
    @SuppressWarnings("unchecked")
    public void declaresEveryCommandPermission() throws IOException {
        Map<String, Map<String, Object>> permissions = (Map<String, Map<String, Object>>) load().get("permissions");
        Map<String, Object> admin = permissions.get(HawkeyeCommand.PERMISSION_ADMIN);
        assertNotNull(admin, HawkeyeCommand.PERMISSION_ADMIN);
        assertEquals("op", admin.get("default"));
        assertTrue(String.valueOf(admin.get("description")).contains("/" + HawkeyeCommand.ROOT));
    }

    private static Map<String, Object> load() throws IOException {
        try (InputStream in = PaperPluginYmlTest.class.getClassLoader().getResourceAsStream("paper-plugin.yml")) {
            assertNotNull(in, "paper-plugin.yml is on the class path");
            return new Yaml().load(in);
        }
    }
}
