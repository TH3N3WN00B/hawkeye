package gg.moonrise.hawkeye.core.service.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;

import gg.moonrise.hawkeye.config.file.LoggingSettings;
import org.junit.jupiter.api.Test;

public class CommandTextTest {

    private final LoggingSettings logging = new LoggingSettings();

    @Test
    public void aCommandIsRecordedAsTyped() {
        assertEquals("/home set base", CommandText.recorded("/home set base", logging));
        assertEquals("/spawn", CommandText.recorded("spawn", logging));
    }

    @Test
    public void aRedactedCommandKeepsOnlyItsLabel() {
        assertEquals("/login", CommandText.recorded("/login hunter2", logging));
        assertEquals("/LOGIN", CommandText.recorded("/LOGIN hunter2", logging));
        assertEquals("/authme:register", CommandText.recorded("/authme:register a b", logging));
        assertEquals("/login", CommandText.recorded("/login", logging));
    }
}
