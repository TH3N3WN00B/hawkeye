package gg.moonrise.hawkeye.core.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.util.redact.Redactor;
import java.io.PrintWriter;
import java.io.Serial;
import java.io.StringWriter;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;
import java.util.Properties;
import java.util.function.Supplier;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

public class RedactingDataSourceTest {

    private static final String USER = "alexander";
    private static final String PASSWORD = "hunter2-pw";
    private static final String URL = "jdbc:fake://db.example:3306/hawkeye";

    @Test
    public void credentialsAreDriverPropertiesOfACopy() throws SQLException {
        FakeDriver driver = new FakeDriver(() -> null);
        Properties properties = properties();
        DataSource dataSource = new RedactingDataSource(driver, URL, properties, redactor());
        properties.setProperty("password", "changed later");
        assertThrows(SQLException.class, dataSource::getConnection);
        assertEquals(URL, driver.url);
        assertEquals(USER, driver.properties.getProperty("user"));
        assertEquals(PASSWORD, driver.properties.getProperty("password"));
        driver.properties.setProperty("user", "the driver may write to it");
        assertThrows(SQLException.class, dataSource::getConnection);
        assertEquals(USER, driver.properties.getProperty("user"), "every connect gets a fresh copy");
    }

    @Test
    public void aRevealingDriverExceptionIsRebuiltWithoutTheCredentials() {
        SQLException thrown = new SQLException("Access denied for user '" + USER + "'@'10.0.0.5' (using password: YES)",
                "28000", 1045, new IllegalStateException("handshake with " + PASSWORD));
        thrown.addSuppressed(new SQLException("suppressed: user=" + USER));
        thrown.setNextException(new SQLException("next: password=" + PASSWORD, "08004", 1129));
        DataSource dataSource = new RedactingDataSource(new FakeDriver(() -> {
            throw new Thrown(thrown);
        }), URL, properties(), redactor());
        SQLException failure = assertThrows(SQLException.class, dataSource::getConnection);
        assertNotSame(thrown, failure);
        assertEquals("28000", failure.getSQLState());
        assertEquals(1045, failure.getErrorCode());
        assertEquals("08004", failure.getNextException().getSQLState());
        assertEquals(1129, failure.getNextException().getErrorCode());
        assertEquals(thrown.getStackTrace().length, failure.getStackTrace().length);
        assertEquals("Access denied for user '" + Redactor.MASK + "'@'10.0.0.5' (using password: YES)",
                failure.getMessage());
        String text = everything(failure);
        assertFalse(text.contains(USER) || text.contains(PASSWORD), text);
        assertTrue(text.contains("handshake with " + Redactor.MASK), text);
    }

    @Test
    public void aCleanExceptionIsRethrownAsItIs() {
        SQLException thrown = new SQLException("Connection refused", "08001");
        DataSource dataSource = new RedactingDataSource(new FakeDriver(() -> {
            throw new Thrown(thrown);
        }), URL, properties(), redactor());
        assertSame(thrown, assertThrows(SQLException.class, dataSource::getConnection));
    }

    @Test
    public void aRevealingRuntimeExceptionIsWrappedAndScrubbed() {
        DataSource dataSource = new RedactingDataSource(new FakeDriver(() -> {
            throw new IllegalArgumentException("Optional parameter password must be Integer, was '" + PASSWORD + "'");
        }), URL, properties(), redactor());
        SQLException failure = assertThrows(SQLException.class, dataSource::getConnection);
        assertTrue(failure.getMessage().startsWith("java.lang.IllegalArgumentException: "), failure.getMessage());
        assertFalse(everything(failure).contains(PASSWORD), everything(failure));

        IllegalStateException clean = new IllegalStateException("no route");
        DataSource cleanSource = new RedactingDataSource(new FakeDriver(() -> {
            throw clean;
        }), URL, properties(), redactor());
        assertSame(clean, assertThrows(IllegalStateException.class, cleanSource::getConnection));
    }

    @Test
    public void aNullConnectionIsReportedWithoutTheProperties() {
        DataSource dataSource = new RedactingDataSource(new FakeDriver(() -> null), URL, properties(), redactor());
        SQLException failure = assertThrows(SQLException.class, dataSource::getConnection);
        assertTrue(failure.getMessage().contains(URL), failure.getMessage());
        assertFalse(everything(failure).contains(USER) || everything(failure).contains(PASSWORD));
    }

    @Test
    public void theRestOfTheDataSourceContract() throws SQLException {
        RedactingDataSource dataSource = new RedactingDataSource(new FakeDriver(() -> null), URL, properties(),
                redactor());
        dataSource.setLoginTimeout(11);
        assertEquals(11, dataSource.getLoginTimeout());
        assertThrows(SQLFeatureNotSupportedException.class, () -> dataSource.getConnection("other", "pw"));
        assertSame(dataSource, dataSource.unwrap(RedactingDataSource.class));
        assertTrue(dataSource.isWrapperFor(DataSource.class));
        assertFalse(dataSource.isWrapperFor(String.class));
        assertThrows(SQLException.class, () -> dataSource.unwrap(String.class));
        PrintWriter writer = new PrintWriter(new StringWriter());
        dataSource.setLogWriter(writer);
        assertSame(writer, dataSource.getLogWriter());
        assertEquals("RedactingDataSource[" + URL + "]", dataSource.toString());
    }

    private static Properties properties() {
        Properties properties = new Properties();
        properties.setProperty("user", USER);
        properties.setProperty("password", PASSWORD);
        return properties;
    }

    private static Redactor redactor() {
        return Redactor.of(List.of(PASSWORD), List.of(USER));
    }

    /** Every message, {@code toString()} and printed stack trace in the graph. */
    private static String everything(Throwable error) {
        StringWriter out = new StringWriter();
        error.printStackTrace(new PrintWriter(out));
        StringBuilder text = new StringBuilder(out.toString()).append(error);
        if (error instanceof SQLException sql) {
            for (Throwable next : sql) {
                text.append(next).append(next.getMessage());
            }
        }
        return text.toString();
    }

    /** Lets a supplier throw a checked {@link SQLException}. */
    private static final class Thrown extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;

        private final transient SQLException error;

        private Thrown(SQLException error) {
            this.error = error;
        }
    }

    /** A driver that records what it was given and answers from a supplier. */
    private static final class FakeDriver implements Driver {
        private final Supplier<Connection> answer;
        private String url;
        private Properties properties;

        private FakeDriver(Supplier<Connection> answer) {
            this.answer = answer;
        }

        @Override
        public Connection connect(String url, Properties info) throws SQLException {
            this.url = url;
            this.properties = info;
            try {
                return answer.get();
            } catch (Thrown thrown) {
                throw thrown.error;
            }
        }

        @Override
        public boolean acceptsURL(String url) {
            return true;
        }

        @Override
        public DriverPropertyInfo @NotNull [] getPropertyInfo(String url, Properties info) {
            return new DriverPropertyInfo[0];
        }

        @Override
        public int getMajorVersion() {
            return 1;
        }

        @Override
        public int getMinorVersion() {
            return 0;
        }

        @Override
        public boolean jdbcCompliant() {
            return false;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }
    }
}
