package gg.moonrise.hawkeye.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

public class CompletionsTest {

    private static final List<String> NAMES = List.of("Notch", "jeb_", "Dinnerbone");

    @Test
    public void matchesAnywhereIgnoringCase() {
        assertEquals(List.of("Notch", "Dinnerbone"), Completions.containing(NAMES, "N"));
        assertEquals(List.of("jeb_"), Completions.containing(NAMES, "EB"));
    }

    @Test
    public void emptyInputMatchesEverything() {
        assertEquals(NAMES, Completions.containing(NAMES, ""));
    }

    @Test
    public void unknownInputMatchesNothing() {
        assertEquals(List.of(), Completions.containing(NAMES, "steve"));
    }
}
