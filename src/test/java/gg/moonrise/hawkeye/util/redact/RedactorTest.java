package gg.moonrise.hawkeye.util.redact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

public class RedactorTest {

    private static final String M = Redactor.MASK;

    @Test
    public void secretsAreReplacedWherever() {
        Redactor redactor = Redactor.of(List.of("hunter2"), List.of());
        assertEquals("pw=" + M + "; again " + M + "x" + M, redactor.scrub("pw=hunter2; again hunter2xhunter2"));
        assertTrue(redactor.reveals("abchunter2def"));
        assertFalse(redactor.reveals("hunter"));
    }

    @Test
    public void namesAreReplacedOnlyOnIdentifierBoundaries() {
        Redactor redactor = Redactor.of(List.of(), List.of("hawkeye"));
        assertEquals("Access denied for user '" + M + "'@'10.0.0.5' on hawkeye_event, xhawkeye, hawkeye1",
                redactor.scrub("Access denied for user 'hawkeye'@'10.0.0.5' on hawkeye_event, xhawkeye, hawkeye1"));
        assertEquals(M + " " + M + ".db " + M, redactor.scrub("hawkeye hawkeye.db hawkeye"));
        assertFalse(redactor.reveals("hawkeye_event"));
        assertTrue(redactor.reveals("role \"hawkeye\" does not exist"));
    }

    @Test
    public void emptyValuesAreIgnored() {
        Redactor redactor = Redactor.of(Arrays.asList("", null), Arrays.asList("", null));
        assertFalse(redactor.hidesAnything());
        assertEquals("nothing to hide", redactor.scrub("nothing to hide"));
        assertFalse(redactor.reveals("nothing to hide"));
        assertSame(Redactor.none(), redactor);
    }

    @Test
    public void theLongerCandidateWins() {
        Redactor redactor = Redactor.of(List.of("pass", "password123"), List.of("pa"));
        assertEquals(M + " " + M + "word " + M, redactor.scrub("password123 password pa"));
        Redactor overlapping = Redactor.of(List.of("abc", "bcd"), List.of());
        assertEquals(M + "d", overlapping.scrub("abcd"), "the first match from the left is taken");
    }

    @Test
    public void nullTextIsEmpty() {
        Redactor redactor = Redactor.of(List.of("hunter2"), List.of("root"));
        assertEquals("", redactor.scrub(null));
        assertFalse(redactor.reveals(null));
    }

    @Test
    public void toStringShowsNoValue() {
        Redactor redactor = Redactor.of(List.of("hunter2"), List.of("root"));
        assertFalse(redactor.toString().contains("hunter2"), redactor.toString());
        assertFalse(redactor.toString().contains("root"), redactor.toString());
    }
}
