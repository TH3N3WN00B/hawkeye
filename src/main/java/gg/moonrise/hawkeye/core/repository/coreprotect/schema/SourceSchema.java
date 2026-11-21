package gg.moonrise.hawkeye.core.repository.coreprotect.schema;

import gg.moonrise.hawkeye.entity.importer.source.SourceSettings;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jetbrains.annotations.NotNull;

/**
 * What one CoreProtect database's tables look like: which exist, from the engine's catalog
 * ({@link SourceDialect#tableExists()}), and which columns they have, from the metadata of
 * {@code SELECT * FROM t WHERE 1 = 0}; both detected once per table. An older CoreProtect lacks some
 * optional tables and columns; readers project a missing column as {@code NULL}
 * ({@link #column(String, String)}). Any failed query is an error, never taken for a missing table. Not
 * thread-safe: one per source connection.
 */
public final class SourceSchema {

    private final @NotNull Connection connection;
    private final @NotNull SourceSettings settings;
    private final @NotNull SourceDialect dialect;
    private final Map<String, Optional<Set<String>>> columns = new HashMap<>();

    /**
     * A schema probe.
     *
     * @param connection the source connection
     * @param settings   the source
     * @param dialect    its dialect
     */
    public SourceSchema(@NotNull Connection connection, @NotNull SourceSettings settings,
            @NotNull SourceDialect dialect) {
        this.connection = connection;
        this.settings = settings;
        this.dialect = dialect;
    }

    /**
     * The dialect.
     *
     * @return the dialect
     */
    public @NotNull SourceDialect dialect() {
        return dialect;
    }

    /**
     * A table's quoted, prefixed name.
     *
     * @param table the name without prefix
     * @return the name for SQL
     */
    public @NotNull String table(@NotNull String table) {
        return dialect.table(settings, table);
    }

    /**
     * Whether a table exists.
     *
     * @param table the name without prefix
     * @return {@code true} when the catalog lists it
     * @throws SQLException when a query fails
     */
    public boolean exists(@NotNull String table) throws SQLException {
        return columns(table).isPresent();
    }

    /**
     * Whether a table has a column.
     *
     * @param table  the name without prefix
     * @param column the column, any case
     * @return {@code true} when the table exists and has it
     * @throws SQLException when a query fails
     */
    public boolean has(@NotNull String table, @NotNull String column) throws SQLException {
        return columns(table).map(names -> names.contains(column.toLowerCase(Locale.ROOT))).orElse(false);
    }

    /**
     * A column for a projection: its quoted name, or {@code NULL} when the table lacks it.
     *
     * @param table  the name without prefix
     * @param column the column
     * @return the SQL expression
     * @throws SQLException when a query fails
     */
    public @NotNull String column(@NotNull String table, @NotNull String column) throws SQLException {
        return has(table, column) ? dialect.quote(column) : "NULL";
    }

    private @NotNull Optional<Set<String>> columns(@NotNull String table) throws SQLException {
        Optional<Set<String>> known = columns.get(table);
        if (known != null) {
            return known;
        }
        Optional<Set<String>> detected = detect(table);
        columns.put(table, detected);
        return detected;
    }

    private @NotNull Optional<Set<String>> detect(@NotNull String table) throws SQLException {
        if (!listed(table)) {
            return Optional.empty();
        }
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM " + table(table)
                + " WHERE 1 = 0");
                ResultSet rows = statement.executeQuery()) {
            ResultSetMetaData meta = rows.getMetaData();
            Set<String> names = new HashSet<>();
            for (int column = 1; column <= meta.getColumnCount(); column++) {
                names.add(meta.getColumnLabel(column).toLowerCase(Locale.ROOT));
            }
            return Optional.of(Set.copyOf(names));
        }
    }

    private boolean listed(@NotNull String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(dialect.tableExists())) {
            statement.setString(1, settings.prefix() + table);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }
}
