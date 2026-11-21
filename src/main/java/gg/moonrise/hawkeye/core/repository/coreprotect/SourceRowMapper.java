package gg.moonrise.hawkeye.core.repository.coreprotect;

import gg.moonrise.hawkeye.entity.importer.SourceRow;
import gg.moonrise.hawkeye.entity.importer.SourceTable;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The columns read from each CoreProtect event table and how a row becomes a {@link SourceRow}. The
 * projection and the mapping are kept together here so they cannot disagree: every query selects
 * {@code rowid, time, user, wid, x, y, z} and then the table's own columns, in the order below, with
 * {@code NULL} for a column the table lacks.
 */
public final class SourceRowMapper {

    private static final List<String> COMMON = List.of("time", "user", "wid", "x", "y", "z");
    private static final int FIRST_OWN = 2 + COMMON.size();

    private final @NotNull SourceSchema schema;

    /**
     * A mapper over a source's schema.
     *
     * @param schema the schema
     */
    public SourceRowMapper(@NotNull SourceSchema schema) {
        this.schema = schema;
    }

    /**
     * The select list of a table.
     *
     * @param table the table
     * @return {@code rowid, "time", ...} with {@code NULL} for missing columns
     * @throws SQLException when the schema cannot be read
     */
    public @NotNull String projection(@NotNull SourceTable table) throws SQLException {
        List<String> expressions = new ArrayList<>();
        expressions.add("rowid");
        for (String column : COMMON) {
            expressions.add(schema.column(table.tableName(), column));
        }
        for (String column : own(table)) {
            expressions.add(schema.column(table.tableName(), column));
        }
        return String.join(", ", expressions);
    }

    /**
     * The row the result set is on.
     *
     * @param table the table it was read from
     * @param rows  the result set, on a row selected with {@link #projection}
     * @return the row
     * @throws SQLException when a column cannot be read
     */
    public @NotNull SourceRow map(@NotNull SourceTable table, @NotNull ResultSet rows) throws SQLException {
        long rowId = rows.getLong(1);
        long time = rows.getLong(2);
        int user = rows.getInt(3);
        int world = rows.getInt(4);
        if (rows.wasNull()) {
            world = SourceRow.NO_WORLD;
        }
        int x = rows.getInt(5);
        int y = rows.getInt(6);
        int z = rows.getInt(7);
        int own = FIRST_OWN;
        return switch (table) {
            case BLOCK -> new SourceRow.Block(rowId, time, user, world, x, y, z, rows.getInt(own),
                    rows.getLong(own + 1), blockData(bytes(rows, own + 2)), rows.getInt(own + 3), rows.getInt(own + 4),
                    null);
            case CONTAINER, ENTITY_CONTAINER -> new SourceRow.Container(table, rowId, time, user, world, x, y, z,
                    rows.getInt(own), rows.getInt(own + 1), bytes(rows, own + 2), rows.getInt(own + 3),
                    rows.getInt(own + 4));
            case ITEM -> new SourceRow.Item(rowId, time, user, world, x, y, z, rows.getInt(own), rows.getInt(own + 1),
                    bytes(rows, own + 2), rows.getInt(own + 3));
            case SIGN -> new SourceRow.Sign(rowId, time, user, world, x, y, z, rows.getInt(own), rows.getInt(own + 1),
                    lines(rows, own + 2), rows.getInt(own + 2 + SourceRow.Sign.LINES));
            case CHAT, COMMAND -> new SourceRow.Message(table, rowId, time, user, world, x, y, z,
                    Objects.requireNonNullElse(rows.getString(own), ""));
            case SESSION -> new SourceRow.Session(rowId, time, user, world, x, y, z, rows.getInt(own));
            case ENTITY_INTERACTION -> new SourceRow.EntityInteraction(rowId, time, user, world, x, y, z,
                    rows.getInt(own), rows.getInt(own + 1));
        };
    }

    /**
     * Parses {@code block.blockdata}: UTF-8 text of comma-separated decimal {@code blockdata_map} ids.
     * Anything that is not such an id is dropped.
     *
     * @param value the column's bytes, or {@code null}
     * @return the ids, empty when there are none
     */
    public static int @NotNull [] blockData(byte @Nullable [] value) {
        if (value == null || value.length == 0) {
            return new int[0];
        }
        String text = new String(value, StandardCharsets.UTF_8);
        return Arrays.stream(text.split(","))
                .map(String::strip)
                .filter(token -> !token.isEmpty() && token.chars().allMatch(Character::isDigit) && token.length() < 10)
                .mapToInt(Integer::parseInt)
                .toArray();
    }

    private static @NotNull List<String> own(@NotNull SourceTable table) {
        return switch (table) {
            case BLOCK -> List.of("type", "data", "blockdata", "action", "rolled_back");
            case CONTAINER, ENTITY_CONTAINER -> List.of("type", "amount", "metadata", "action", "rolled_back");
            case ITEM -> List.of("type", "amount", "data", "action");
            case SIGN -> List.of("action", "face", "line_1", "line_2", "line_3", "line_4", "line_5", "line_6",
                    "line_7", "line_8", "rolled_back");
            case CHAT, COMMAND -> List.of("message");
            case SESSION -> List.of("action");
            case ENTITY_INTERACTION -> List.of("type", "action");
        };
    }

    private byte @Nullable [] bytes(@NotNull ResultSet rows, int column) throws SQLException {
        return schema.dialect().bytes(rows, column);
    }

    private static @NotNull List<String> lines(@NotNull ResultSet rows, int first) throws SQLException {
        List<String> lines = new ArrayList<>(SourceRow.Sign.LINES);
        for (int line = 0; line < SourceRow.Sign.LINES; line++) {
            lines.add(Objects.requireNonNullElse(rows.getString(first + line), ""));
        }
        return lines;
    }
}
