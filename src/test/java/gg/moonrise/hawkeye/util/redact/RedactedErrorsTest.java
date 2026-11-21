package gg.moonrise.hawkeye.util.redact;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;

public class RedactedErrorsTest {

    private static final Redactor REDACTOR = Redactor.of(List.of("hunter2"), List.of("alex"));

    @Test
    public void aCleanExceptionIsKept() {
        SQLException clean = new SQLException("Connection refused", "08001", 0, new RuntimeException("timeout"));
        assertSame(clean, RedactedErrors.safe(clean, REDACTOR));
        RuntimeException runtime = new IllegalStateException("closed");
        assertSame(runtime, RedactedErrors.safe((Throwable) runtime, REDACTOR));
    }

    @Test
    public void aRevealingGraphIsRebuiltEverywhere() {
        SQLException top = new SQLException("Access denied for user 'alex'", "28000", 1045,
                new IllegalArgumentException("bad password hunter2"));
        top.addSuppressed(new SQLException("suppressed alex"));
        top.setNextException(new SQLException("next hunter2", "08004", 7));
        SQLException safe = RedactedErrors.safe(top, REDACTOR);
        assertNotSame(top, safe);
        assertEquals("28000", safe.getSQLState());
        assertEquals(1045, safe.getErrorCode());
        assertArrayEquals(top.getStackTrace(), safe.getStackTrace());
        assertEquals("08004", safe.getNextException().getSQLState());
        assertEquals(7, safe.getNextException().getErrorCode());
        assertEquals("java.lang.IllegalArgumentException: bad password " + Redactor.MASK, safe.getCause().getMessage());
        assertEquals(1, safe.getSuppressed().length);
        String printed = printed(safe) + printed(safe.getNextException());
        assertFalse(printed.contains("alex") || printed.contains("hunter2"), printed);
        assertFalse(RedactedErrors.reveals(safe, REDACTOR));
    }

    @Test
    public void aRevealingRuntimeExceptionBecomesAnSqlException() {
        Throwable safe = RedactedErrors.safe(new IllegalArgumentException("user alex"), REDACTOR);
        assertInstanceOf(SQLException.class, safe);
        assertEquals("java.lang.IllegalArgumentException: user " + Redactor.MASK, safe.getMessage());
    }

    @Test
    public void cyclesEnd() {
        SQLException first = new SQLException("first alex");
        SQLException second = new SQLException("second", first);
        first.setNextException(second);
        first.addSuppressed(second);
        assertTrue(RedactedErrors.reveals(second, REDACTOR));
        SQLException safe = RedactedErrors.rebuild(second, REDACTOR);
        assertFalse(printed(safe).contains("alex"), printed(safe));
    }

    private static String printed(Throwable error) {
        StringWriter out = new StringWriter();
        error.printStackTrace(new PrintWriter(out));
        return out.toString();
    }
}
