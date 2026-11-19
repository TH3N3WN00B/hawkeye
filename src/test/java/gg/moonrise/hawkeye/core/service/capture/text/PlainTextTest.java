package gg.moonrise.hawkeye.core.service.capture.text;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.Test;

public class PlainTextTest {

    @Test
    public void aComponentBecomesItsPlainTextOnOneLine() {
        Component reason = Component.text("Banned  \n\n", NamedTextColor.RED)
                .append(Component.text("  appeal at example.org\r\nfor help "));

        assertEquals("Banned appeal at example.org for help", PlainText.oneLine(reason));
    }

    @Test
    public void spacesInsideALineAreKept() {
        assertEquals("a  b", PlainText.oneLine("a  b"));
    }

    @Test
    public void cappingKeepsShortTextAndNeverSplitsASurrogatePair() {
        assertEquals("short", PlainText.capped("short", 10));
        assertEquals("abc", PlainText.capped("abcdef", 3));
        String emoji = "ab😀";
        assertEquals("ab", PlainText.capped(emoji, 3));
        assertEquals(emoji, PlainText.capped(emoji, 4));
    }
}
