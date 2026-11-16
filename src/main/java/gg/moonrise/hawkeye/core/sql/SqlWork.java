package gg.moonrise.hawkeye.core.sql;

import java.sql.Connection;
import java.sql.SQLException;
import org.jetbrains.annotations.NotNull;

/**
 * Work on a borrowed connection.
 *
 * @param <T> the result type
 */
@FunctionalInterface
public interface SqlWork<T> {

    /**
     * Runs the work.
     *
     * @param connection the borrowed connection; never close it
     * @return the result
     * @throws SQLException when a statement fails
     */
    T run(@NotNull Connection connection) throws SQLException;
}
