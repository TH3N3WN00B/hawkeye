package gg.moonrise.hawkeye.core.repository.coreprotect.source;

import gg.moonrise.hawkeye.core.repository.coreprotect.source.CoreProtectFixture.Layout;
import gg.moonrise.hawkeye.entity.importer.source.SourceType;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.NotNull;

/**
 * The CoreProtect tables {@link CoreProtectFixture} creates, written from CoreProtect 25.x's DDL
 * ({@code Database.createSQLiteTableStructures}, {@code createMySQLTableStructures},
 * {@code DuckDBDatabase.createTables}) and, for ClickHouse, from the column names and types of its
 * compatibility views ({@code ClickHouseSchema}). Only what a reader may touch is modelled; columns
 * CoreProtect has and the import never reads ({@code meta}, sign colours) are kept where they shape a
 * row.
 */
public final class CoreProtectTables {

    /** A column's kind, mapped to each engine's type. */
    public enum Kind {
        /** A Unix time in seconds. */
        TIME,
        /** A 32-bit integer. */
        INT,
        /** A 64-bit integer. */
        LONG,
        /** A small code (action, flags). */
        TINY,
        /** Text. */
        TEXT,
        /** Bytes. */
        BLOB
    }

    /**
     * A column.
     *
     * @param name its name
     * @param kind its kind
     */
    public record Column(@NotNull String name, @NotNull Kind kind) {
    }

    /**
     * A table.
     *
     * @param name      its name without prefix
     * @param columns   its columns, without the row id
     * @param idKey     whether SQLite declares {@code id INTEGER PRIMARY KEY} (an alias of the rowid)
     * @param timeOrder whether ClickHouse sorts it by time (event tables) rather than by rowid
     */
    public record Table(@NotNull String name, @NotNull List<Column> columns, boolean idKey, boolean timeOrder) {

        /**
         * Copies the columns.
         */
        public Table {
            columns = List.copyOf(columns);
        }

        /**
         * Whether the table has a column.
         *
         * @param column the name
         * @return {@code true} when it does
         */
        public boolean has(@NotNull String column) {
            return columns.stream().anyMatch(candidate -> candidate.name().equals(column));
        }
    }

    private static final Set<String> LEGACY_MISSING_TABLES = Set.of("entity_container", "entity_interaction",
            "entity_spawn", "blockdata_map", "database_lock");
    private static final Set<String> LEGACY_MISSING_SIGN = Set.of("color", "color_secondary", "data", "waxed", "face",
            "line_5", "line_6", "line_7", "line_8", "rolled_back");

    private CoreProtectTables() {
    }

    /**
     * Every table of a layout.
     *
     * @param layout the layout
     * @return the tables
     */
    public static @NotNull List<Table> tables(@NotNull Layout layout) {
        List<Table> tables = new ArrayList<>();
        for (Table table : current()) {
            if (layout == Layout.LEGACY) {
                if (LEGACY_MISSING_TABLES.contains(table.name())) {
                    continue;
                }
                table = legacy(table);
            }
            tables.add(table);
        }
        return tables;
    }

    /**
     * One table of a layout.
     *
     * @param layout the layout
     * @param name   the table without prefix
     * @return the table
     * @throws IllegalArgumentException when the layout has no such table
     */
    public static @NotNull Table table(@NotNull Layout layout, @NotNull String name) {
        return tables(layout).stream().filter(table -> table.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("the " + layout + " layout has no table " + name));
    }

    /**
     * The statements that create a layout's tables on an engine.
     *
     * @param engine the engine
     * @param prefix the table prefix
     * @param layout the layout
     * @return the statements
     */
    public static @NotNull List<String> ddl(@NotNull SourceType engine, @NotNull String prefix, @NotNull Layout layout) {
        List<String> statements = new ArrayList<>();
        for (Table table : tables(layout)) {
            statements.add(create(engine, prefix, table));
        }
        return statements;
    }

    private static @NotNull String create(@NotNull SourceType engine, @NotNull String prefix, @NotNull Table table) {
        List<String> columns = new ArrayList<>();
        switch (engine) {
            case SQLITE -> {
                if (table.idKey()) {
                    columns.add("id INTEGER PRIMARY KEY ASC");
                }
            }
            case MYSQL -> columns.add("rowid bigint NOT NULL AUTO_INCREMENT");
            case DUCKDB -> columns.add("rowid BIGINT NOT NULL");
            case CLICKHOUSE -> columns.add("rowid UInt64");
        }
        for (Column column : table.columns()) {
            columns.add(quote(engine, column.name()) + " " + type(engine, column.kind(), column.name()));
        }
        if (engine == SourceType.MYSQL) {
            columns.add("PRIMARY KEY (rowid)");
        }
        String name = quote(engine, prefix + table.name());
        String body = " (" + String.join(", ", columns) + ")";
        return switch (engine) {
            case SQLITE, DUCKDB -> "CREATE TABLE IF NOT EXISTS " + name + body;
            case MYSQL -> "CREATE TABLE IF NOT EXISTS " + name + body + " ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4";
            case CLICKHOUSE -> "CREATE TABLE IF NOT EXISTS " + name + body + " ENGINE = MergeTree ORDER BY "
                    + (table.timeOrder() ? "(time, rowid)" : "rowid");
        };
    }

    private static @NotNull String type(@NotNull SourceType engine, @NotNull Kind kind, @NotNull String column) {
        return switch (engine) {
            case SQLITE -> switch (kind) {
                case TIME, INT, LONG, TINY -> "INTEGER";
                case TEXT -> "TEXT";
                case BLOB -> "BLOB";
            };
            case MYSQL -> switch (kind) {
                case TIME, INT -> "int";
                case LONG -> "bigint";
                case TINY -> "tinyint";
                case TEXT -> column.equals("message") ? "varchar(16000)" : "varchar(255)";
                case BLOB -> "mediumblob";
            };
            case DUCKDB -> switch (kind) {
                case TIME, INT -> "INTEGER";
                case LONG -> "BIGINT";
                case TINY -> "TINYINT";
                case TEXT -> "VARCHAR";
                case BLOB -> "BLOB";
            };
            // The views' types: time is UInt32 and never NULL, everything else is nullable.
            case CLICKHOUSE -> switch (kind) {
                case TIME -> "UInt32";
                case INT -> column.equals("x") || column.equals("y") || column.equals("z") || column.equals("amount")
                        ? "Nullable(Int32)" : "Nullable(UInt32)";
                case LONG -> column.equals("data") ? "Nullable(Int64)" : "Nullable(UInt64)";
                case TINY -> "Nullable(UInt8)";
                case TEXT, BLOB -> "Nullable(String)";
            };
        };
    }

    private static @NotNull String quote(@NotNull SourceType engine, @NotNull String identifier) {
        char quote = engine == SourceType.MYSQL || engine == SourceType.CLICKHOUSE ? '`' : '"';
        return quote + identifier + quote;
    }

    private static @NotNull Table legacy(@NotNull Table table) {
        return switch (table.name()) {
            case "block" -> without(table, Set.of("blockdata"));
            case "sign" -> without(table, LEGACY_MISSING_SIGN);
            default -> table;
        };
    }

    private static @NotNull Table without(@NotNull Table table, @NotNull Set<String> columns) {
        return new Table(table.name(), table.columns().stream().filter(column -> !columns.contains(column.name()))
                .toList(), table.idKey(), table.timeOrder());
    }

    private static @NotNull List<Table> current() {
        List<Column> place = List.of(c("time", Kind.TIME), c("user", Kind.INT), c("wid", Kind.INT), c("x", Kind.INT),
                c("y", Kind.INT), c("z", Kind.INT));
        List<Table> tables = new ArrayList<>();
        tables.add(event("block", place, c("type", Kind.INT), c("data", Kind.LONG), c("meta", Kind.BLOB),
                c("blockdata", Kind.BLOB), c("action", Kind.TINY), c("rolled_back", Kind.TINY)));
        tables.add(event("chat", place, c("message", Kind.TEXT)));
        tables.add(event("command", place, c("message", Kind.TEXT)));
        tables.add(event("container", place, c("type", Kind.INT), c("data", Kind.INT), c("amount", Kind.INT),
                c("metadata", Kind.BLOB), c("action", Kind.TINY), c("rolled_back", Kind.TINY)));
        tables.add(new Table("entity_container", concat(List.of(c("time", Kind.TIME), c("user", Kind.INT),
                c("entity_spawn_rowid", Kind.LONG), c("wid", Kind.INT), c("x", Kind.INT), c("y", Kind.INT),
                c("z", Kind.INT)), c("type", Kind.INT), c("data", Kind.INT), c("amount", Kind.INT),
                c("metadata", Kind.BLOB), c("action", Kind.TINY), c("rolled_back", Kind.TINY)), false, true));
        tables.add(new Table("entity_interaction", concat(List.of(c("time", Kind.TIME), c("user", Kind.INT),
                c("entity_spawn_rowid", Kind.LONG), c("wid", Kind.INT), c("x", Kind.INT), c("y", Kind.INT),
                c("z", Kind.INT)), c("type", Kind.INT), c("action", Kind.TINY), c("metadata", Kind.BLOB),
                c("rolled_back", Kind.TINY)), false, true));
        tables.add(event("item", place, c("type", Kind.INT), c("data", Kind.BLOB), c("amount", Kind.INT),
                c("action", Kind.TINY), c("rolled_back", Kind.TINY)));
        tables.add(new Table("database_lock", List.of(c("status", Kind.TINY), c("time", Kind.TIME)), false, false));
        tables.add(new Table("entity_spawn", List.of(c("time", Kind.TIME), c("block_rowid", Kind.LONG),
                c("kill_rowid", Kind.LONG), c("uuid", Kind.TEXT), c("wid", Kind.INT), c("removed", Kind.TINY)), true,
                false));
        tables.add(dictionary("entity_map", "entity"));
        tables.add(dictionary("material_map", "material"));
        tables.add(dictionary("blockdata_map", "data"));
        tables.add(event("session", place, c("action", Kind.TINY)));
        tables.add(event("sign", place, c("action", Kind.TINY), c("color", Kind.INT), c("color_secondary", Kind.INT),
                c("data", Kind.TINY), c("waxed", Kind.TINY), c("face", Kind.TINY), c("line_1", Kind.TEXT),
                c("line_2", Kind.TEXT), c("line_3", Kind.TEXT), c("line_4", Kind.TEXT), c("line_5", Kind.TEXT),
                c("line_6", Kind.TEXT), c("line_7", Kind.TEXT), c("line_8", Kind.TEXT), c("rolled_back", Kind.TINY)));
        tables.add(new Table("user", List.of(c("time", Kind.TIME), c("user", Kind.TEXT), c("uuid", Kind.TEXT)), true,
                false));
        tables.add(new Table("version", List.of(c("time", Kind.TIME), c("version", Kind.TEXT)), false, false));
        tables.add(dictionary("world", "world"));
        return tables;
    }

    private static @NotNull Table event(@NotNull String name, @NotNull List<Column> place, @NotNull Column... own) {
        return new Table(name, concat(place, own), false, true);
    }

    private static @NotNull Table dictionary(@NotNull String name, @NotNull String column) {
        return new Table(name, List.of(c("id", Kind.INT), c(column, Kind.TEXT)), false, false);
    }

    private static @NotNull List<Column> concat(@NotNull List<Column> first, @NotNull Column... rest) {
        List<Column> all = new ArrayList<>(first);
        all.addAll(List.of(rest));
        return all;
    }

    private static @NotNull Column c(@NotNull String name, @NotNull Kind kind) {
        return new Column(name, kind);
    }
}
