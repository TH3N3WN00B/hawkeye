package gg.moonrise.hawkeye.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

public class HawkeyePluginLoaderTest {

    @Test
    public void resolvesExactlyTheCatalogVersions() {
        String catalog = System.getProperty("hawkeye.runtime.libraries");
        assertNotNull(catalog, "the build passes the catalog coordinates");
        assertEquals(List.copyOf(Arrays.asList(catalog.split(","))), new HawkeyePluginLoader().libraries());
    }
}
