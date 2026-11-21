package gg.moonrise.hawkeye.util.text;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

public class DurationsTest {

    @Test
    public void parsesCompoundAndDecimalDurations() {
        assertEquals(Optional.of(Duration.ofDays(17).plusHours(3)), Durations.parse("2w3d3h"));
        assertEquals(Optional.of(Duration.ofMinutes(90)), Durations.parse("1.5h"));
        assertEquals(Optional.of(Duration.ofSeconds(45)), Durations.parse("45"));
        assertEquals(Optional.of(Duration.ofMinutes(5)), Durations.parse("5M"));
    }

    @Test
    public void rejectsAnythingElse() {
        for (String input : new String[] {"", "h", "-1h", "1x", "1..5h", "0s", "abc"}) {
            assertEquals(Optional.empty(), Durations.parse(input), input);
        }
    }

    @Test
    public void formatsTheTwoLargestUnits() {
        assertEquals("3d4h", Durations.format(Duration.ofDays(3).plusHours(4).plusMinutes(5)));
        assertEquals("12m5s", Durations.format(Duration.ofSeconds(725)));
        assertEquals("1w", Durations.format(Duration.ofDays(7).plusMinutes(3)));
        assertEquals("0s", Durations.format(Duration.ofMillis(300)));
    }
}
