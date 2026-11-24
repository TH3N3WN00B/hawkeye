package gg.moonrise.hawkeye.config.file;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class LoggingSettingsTest {

    /** The snapshot switches by the field (and so the YAML key) that holds them. */
    private static final Map<LogCategory, String> SNAPSHOTS = Map.of(
            LogCategory.JOIN_SNAPSHOTS, "joinSnapshots",
            LogCategory.QUIT_SNAPSHOTS, "quitSnapshots",
            LogCategory.TELEPORT_SNAPSHOTS, "teleportSnapshots",
            LogCategory.DEATH_SNAPSHOTS, "deathSnapshots");

    @Test
    public void inventorySnapshotsAreOnByDefault() {
        LoggingSettings settings = new LoggingSettings();
        SNAPSHOTS.keySet().forEach(category -> assertTrue(settings.enabled(category), category.name()));
    }

    @Test
    public void eachSnapshotSwitchControlsItsOwnCategory() throws ReflectiveOperationException {
        for (Map.Entry<LogCategory, String> entry : SNAPSHOTS.entrySet()) {
            LoggingSettings settings = new LoggingSettings();
            Field field = LoggingSettings.class.getDeclaredField(entry.getValue());
            field.setAccessible(true);
            field.setBoolean(settings, false);
            for (LogCategory category : SNAPSHOTS.keySet()) {
                assertEquals(category != entry.getKey(), settings.enabled(category),
                        entry.getValue() + " " + category);
            }
        }
    }

    @Test
    public void theSnapshotAndVirtualContainerSwitchesFollowTheConsoleCommandsSwitch() {
        // ConfigLib writes keys in field order; existing keys keep their place.
        List<String> names = Arrays.stream(LoggingSettings.class.getDeclaredFields()).map(Field::getName)
                .toList();
        int console = names.indexOf("consoleCommands");
        assertEquals(List.of("joinSnapshots", "quitSnapshots", "teleportSnapshots", "deathSnapshots",
                "virtualContainers", "liquidTracking"), names.subList(console + 1, console + 7));
    }

    @Test
    public void virtualContainersAreOnByDefaultAndHaveTheirOwnSwitch() throws ReflectiveOperationException {
        LoggingSettings settings = new LoggingSettings();
        assertTrue(settings.enabled(LogCategory.VIRTUAL_CONTAINERS));
        Field field = LoggingSettings.class.getDeclaredField("virtualContainers");
        field.setAccessible(true);
        field.setBoolean(settings, false);
        assertFalse(settings.enabled(LogCategory.VIRTUAL_CONTAINERS));
        assertTrue(settings.enabled(LogCategory.CONTAINERS));
    }
}
