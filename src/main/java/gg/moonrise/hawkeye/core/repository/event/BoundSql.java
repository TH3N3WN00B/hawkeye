package gg.moonrise.hawkeye.core.repository.event;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import org.jetbrains.annotations.NotNull;

/**
 * A statement and its parameters, built by {@link EventSql}.
 *
 * @param sql        the SQL with {@code ?} placeholders
 * @param parameters the values, {@link Integer} or {@link Long}, in placeholder order
 */
public record BoundSql(@NotNull String sql, @NotNull List<Object> parameters) {

    /**
     * Copies the parameters.
     *
     * @param sql        the SQL
     * @param parameters the values
     */
    public BoundSql {
        parameters = List.copyOf(parameters);
    }

    /**
     * Binds every parameter to a prepared statement of {@link #sql()}.
     *
     * @param statement the statement
     * @return the index of the next free placeholder
     * @throws SQLException when binding fails
     */
    public int bind(@NotNull PreparedStatement statement) throws SQLException {
        int index = 1;
        for (Object parameter : parameters) {
            switch (parameter) {
                case Integer value -> statement.setInt(index, value);
                case Long value -> statement.setLong(index, value);
                default -> throw new IllegalStateException("unsupported parameter " + parameter.getClass());
            }
            index++;
        }
        return index;
    }
}
