package gg.moonrise.hawkeye.core.repository.coreprotect.source;

import gg.moonrise.hawkeye.core.repository.coreprotect.driver.DriverResolver;
import gg.moonrise.hawkeye.entity.importer.source.SourceRow;
import gg.moonrise.hawkeye.entity.importer.source.SourceSettings;
import gg.moonrise.hawkeye.entity.importer.source.SourceType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.sqlite.JDBC;

/**
 * A CoreProtect database built for tests, on any of CoreProtect's four engines, with readable insert
 * helpers. The tables are created the way CoreProtect (upstream 25.x) creates them: on SQLite without an
 * explicit key on the event tables (the implicit {@code rowid} is the row id) and with
 * {@code id INTEGER PRIMARY KEY} on {@code user} and {@code entity_spawn}; on MySQL with a
 * {@code rowid} auto-increment key; on DuckDB with an explicit {@code rowid} column. ClickHouse stores
 * CoreProtect's history in one table only CoreProtect can fill and exposes it through compatibility
 * views; the fixture creates plain tables with the views' column names and types instead.
 *
 * <p>Every helper inserts one row and returns what an event row refers to it by: dictionary helpers
 * ({@link #user}, {@link #world}, {@link #material}, {@link #blockData}, {@link #entity}) return the id
 * event rows use (a user's rowid, the logical {@code id} of the others, numbered from 1); event helpers
 * return the row's rowid. Rowids are assigned by the fixture, one table counter each, so tests can leave
 * gaps ({@link #skipRowIds}) and know every rowid in advance. A {@code null} argument is stored as SQL
 * NULL, and a value for a column the {@link Layout} lacks is dropped.
 *
 * <pre>{@code
 * try (CoreProtectFixture db = CoreProtectFixture.sqlite(folder.resolve("database.db"), Layout.CURRENT)) {
 *     int alex = db.user("Alex", "00000000-0000-0000-0000-00000000000a");
 *     int world = db.world("world");
 *     int stone = db.material("minecraft:stone");
 *     db.version(1_767_225_600, "25.0");
 *     long row = db.block(1_767_225_601, alex, CoreProtectFixture.at(world, 1, 64, 2), stone, 0, null, 1, 0);
 *     // open db.settings() with CoreProtectSource.open(...)
 * }
 * }</pre>
 *
 * <p>SQLite and DuckDB fixtures own their connection; close the fixture before opening a DuckDB file
 * read-only (DuckDB refuses a second connection to one file with another configuration). Server engine
 * fixtures use the test's connection, under a table prefix, and {@link #drop()} removes their tables.
 */
public final class CoreProtectFixture implements AutoCloseable {

    /** Which tables and columns exist. */
    public enum Layout {
        /** CoreProtect 25.x: every table and column. */
        CURRENT,
        /**
         * An older CoreProtect: no {@code entity_container}, {@code entity_interaction},
         * {@code entity_spawn}, {@code blockdata_map} or {@code database_lock}; {@code block} without
         * {@code blockdata}; {@code sign} without colours, {@code face}, back lines and {@code rolled_back}.
         */
        LEGACY
    }

    /**
     * A block position in a world.
     *
     * @param world the {@code world.id}, or {@code null} for a NULL {@code wid}
     * @param x     x
     * @param y     y
     * @param z     z
     */
    public record Place(@Nullable Integer world, int x, int y, int z) {
    }

    private final @NotNull SourceType engine;
    private final @NotNull Connection connection;
    private final boolean owned;
    private final @NotNull String prefix;
    private final @NotNull Layout layout;
    private final @Nullable Path file;
    private final Map<String, Long> rowIds = new HashMap<>();
    private final Map<String, Integer> ids = new HashMap<>();

    private CoreProtectFixture(@NotNull SourceType engine, @NotNull Connection connection, boolean owned,
            @NotNull String prefix, @NotNull Layout layout, @Nullable Path file) {
        this.engine = engine;
        this.connection = connection;
        this.owned = owned;
        this.prefix = prefix;
        this.layout = layout;
        this.file = file;
    }

    /**
     * A SQLite CoreProtect database in a new file.
     *
     * @param file   the file ({@code database.db} in a temporary folder)
     * @param layout which tables and columns exist
     * @return the fixture, owning its connection
     * @throws SQLException when creating the tables fails
     */
    public static @NotNull CoreProtectFixture sqlite(@NotNull Path file, @NotNull Layout layout) throws SQLException {
        Connection connection = new JDBC().connect("jdbc:sqlite:" + file.toAbsolutePath(), new Properties());
        return create(SourceType.SQLITE, connection, true, SourceSettings.DEFAULT_PREFIX, layout, file);
    }

    /**
     * A DuckDB CoreProtect database in a new file.
     *
     * @param file   the file
     * @param driver DuckDB's driver ({@link DriverResolver})
     * @param layout which tables and columns exist
     * @return the fixture, owning its connection: close it before reading the file
     * @throws SQLException when creating the tables fails
     */
    public static @NotNull CoreProtectFixture duckdb(@NotNull Path file, @NotNull Driver driver,
            @NotNull Layout layout) throws SQLException {
        Connection connection = Objects.requireNonNull(driver.connect("jdbc:duckdb:" + file.toAbsolutePath(),
                new Properties()), "the DuckDB driver accepts jdbc:duckdb URLs");
        return create(SourceType.DUCKDB, connection, true, SourceSettings.DEFAULT_PREFIX, layout, file);
    }

    /**
     * A CoreProtect database on a server engine, in the database the connection is on.
     *
     * @param engine     {@link SourceType#MYSQL} or {@link SourceType#CLICKHOUSE}
     * @param connection a connection the tables may be created on (not closed by the fixture)
     * @param prefix     the table prefix
     * @param layout     which tables and columns exist
     * @return the fixture
     * @throws SQLException when creating the tables fails
     */
    public static @NotNull CoreProtectFixture server(@NotNull SourceType engine, @NotNull Connection connection,
            @NotNull String prefix, @NotNull Layout layout) throws SQLException {
        return create(engine, connection, false, prefix, layout, null);
    }

    /**
     * A place.
     *
     * @param world the {@code world.id}, or {@code null} for NULL
     * @param x     x
     * @param y     y
     * @param z     z
     * @return the place
     */
    public static @NotNull Place at(@Nullable Integer world, int x, int y, int z) {
        return new Place(world, x, y, z);
    }

    /**
     * The settings that open a file engine's database.
     *
     * @return the settings
     * @throws IllegalStateException for a server engine (the test knows its own server)
     */
    public @NotNull SourceSettings settings() {
        if (file == null) {
            throw new IllegalStateException("a server engine's settings come from the test");
        }
        return SourceSettings.ofFile(engine, file);
    }

    /**
     * The connection the fixture writes with.
     *
     * @return the connection
     */
    public @NotNull Connection connection() {
        return connection;
    }

    /**
     * A {@code user} row: a player, or a {@code #cause} with a NULL UUID.
     *
     * @param name the name
     * @param uuid the UUID text (any text: CoreProtect does not check it), or {@code null}
     * @return the user's rowid, which event rows store in {@code user}
     * @throws SQLException when inserting fails
     */
    public int user(@NotNull String name, @Nullable String uuid) throws SQLException {
        return (int) insert("user", values("time", 0, "user", name, "uuid", uuid));
    }

    /**
     * A {@code world} row.
     *
     * @param name the world name
     * @return its {@code id}
     * @throws SQLException when inserting fails
     */
    public int world(@NotNull String name) throws SQLException {
        return named("world", "world", name);
    }

    /**
     * A {@code material_map} row.
     *
     * @param name the namespaced material, e.g. {@code minecraft:stone}
     * @return its {@code id}
     * @throws SQLException when inserting fails
     */
    public int material(@NotNull String name) throws SQLException {
        return named("material_map", "material", name);
    }

    /**
     * A {@code blockdata_map} row.
     *
     * @param token one block state property, e.g. {@code facing=north}
     * @return its {@code id}
     * @throws SQLException when inserting fails
     */
    public int blockData(@NotNull String token) throws SQLException {
        return named("blockdata_map", "data", token);
    }

    /**
     * An {@code entity_map} row.
     *
     * @param name the entity type without namespace, e.g. {@code zombie}
     * @return its {@code id}
     * @throws SQLException when inserting fails
     */
    public int entity(@NotNull String name) throws SQLException {
        return named("entity_map", "entity", name);
    }

    /**
     * An {@code entity_spawn} row.
     *
     * @param uuid the entity's UUID text, or {@code null}
     * @return its rowid, which a spawn's {@code block.data} refers to
     * @throws SQLException when inserting fails
     */
    public long entitySpawn(@Nullable String uuid) throws SQLException {
        return insert("entity_spawn", values("time", 0, "uuid", uuid, "removed", 0));
    }

    /**
     * A {@code version} row; the first one is CoreProtect's install record.
     *
     * @param time    Unix seconds
     * @param version the version text, e.g. {@code 25.0}
     * @throws SQLException when inserting fails
     */
    public void version(long time, @NotNull String version) throws SQLException {
        insert("version", values("time", time, "version", version));
    }

    /**
     * A {@code database_lock} row.
     *
     * @param status 0 unlocked, 1 locked by a running server, 2 migration incomplete
     * @throws SQLException when inserting fails
     */
    public void lockStatus(int status) throws SQLException {
        insert("database_lock", values("status", status, "time", 0));
    }

    /**
     * A {@code block} row.
     *
     * @param time       Unix seconds
     * @param user       the user rowid, or {@code null}
     * @param at         where
     * @param type       the {@code material_map} id (an {@code entity_map} id for a kill or spawn)
     * @param data       the data value (a spawn's {@code entity_spawn} rowid)
     * @param blockData  the {@code blockdata} text, e.g. {@code "3,7"}, or {@code null}
     * @param action     the action code
     * @param rolledBack the rolled-back bits
     * @return the rowid
     * @throws SQLException when inserting fails
     */
    public long block(long time, @Nullable Integer user, @NotNull Place at, int type, long data,
            @Nullable String blockData, int action, int rolledBack) throws SQLException {
        Map<String, Object> values = event(time, user, at);
        values.putAll(values("type", type, "data", data, "blockdata",
                blockData == null ? null : blockData.getBytes(StandardCharsets.UTF_8), "action", action,
                "rolled_back", rolledBack));
        return insert("block", values);
    }

    /**
     * A {@code container} row.
     *
     * @param time       Unix seconds
     * @param user       the user rowid, or {@code null}
     * @param at         where
     * @param type       the item's {@code material_map} id
     * @param amount     the count
     * @param metadata   the metadata blob, or {@code null}
     * @param action     0 removed, 1 added
     * @param rolledBack the rolled-back bits
     * @return the rowid
     * @throws SQLException when inserting fails
     */
    public long container(long time, @Nullable Integer user, @NotNull Place at, int type, int amount,
            byte @Nullable [] metadata, int action, int rolledBack) throws SQLException {
        return insert("container", containerValues(time, user, at, type, amount, metadata, action, rolledBack));
    }

    /**
     * An {@code entity_container} row.
     *
     * @param time       Unix seconds
     * @param user       the user rowid, or {@code null}
     * @param spawn      the {@code entity_spawn} rowid of the entity
     * @param at         where
     * @param type       the item's {@code material_map} id
     * @param amount     the count
     * @param metadata   the metadata blob, or {@code null}
     * @param action     0 removed, 1 added
     * @param rolledBack the rolled-back bits
     * @return the rowid
     * @throws SQLException when inserting fails
     */
    public long entityContainer(long time, @Nullable Integer user, long spawn, @NotNull Place at, int type,
            int amount, byte @Nullable [] metadata, int action, int rolledBack) throws SQLException {
        Map<String, Object> values = containerValues(time, user, at, type, amount, metadata, action, rolledBack);
        values.put("entity_spawn_rowid", spawn);
        return insert("entity_container", values);
    }

    /**
     * An {@code item} row.
     *
     * @param time     Unix seconds
     * @param user     the user rowid, or {@code null}
     * @param at       where
     * @param type     the item's {@code material_map} id
     * @param amount   the count
     * @param metadata the {@code data} blob, or {@code null}
     * @param action   the action code (2 to 12)
     * @return the rowid
     * @throws SQLException when inserting fails
     */
    public long item(long time, @Nullable Integer user, @NotNull Place at, int type, int amount,
            byte @Nullable [] metadata, int action) throws SQLException {
        Map<String, Object> values = event(time, user, at);
        values.putAll(values("type", type, "data", metadata, "amount", amount, "action", action, "rolled_back", 0));
        return insert("item", values);
    }

    /**
     * A {@code sign} row.
     *
     * @param time       Unix seconds
     * @param user       the user rowid, or {@code null}
     * @param at         where
     * @param action     0 break, 1 place, 2 edit, 3 "before" snapshot
     * @param face       0 front, 1 back, or {@code null}
     * @param lines      up to 8 lines ({@code line_1} first), {@code null} entries stored as NULL
     * @param rolledBack 0 or 1
     * @return the rowid
     * @throws SQLException when inserting fails
     */
    public long sign(long time, @Nullable Integer user, @NotNull Place at, int action, @Nullable Integer face,
            @NotNull List<@Nullable String> lines, int rolledBack) throws SQLException {
        Map<String, Object> values = event(time, user, at);
        values.putAll(values("action", action, "color", 0, "color_secondary", 0, "data", 0, "waxed", 0,
                "face", face, "rolled_back", rolledBack));
        for (int line = 0; line < SourceRow.Sign.LINES; line++) {
            values.put("line_" + (line + 1), line < lines.size() ? lines.get(line) : null);
        }
        return insert("sign", values);
    }

    /**
     * A {@code chat} row.
     *
     * @param time    Unix seconds
     * @param user    the user rowid, or {@code null}
     * @param at      where
     * @param message the text, or {@code null}
     * @return the rowid
     * @throws SQLException when inserting fails
     */
    public long chat(long time, @Nullable Integer user, @NotNull Place at, @Nullable String message)
            throws SQLException {
        Map<String, Object> values = event(time, user, at);
        values.put("message", message);
        return insert("chat", values);
    }

    /**
     * A {@code command} row.
     *
     * @param time    Unix seconds
     * @param user    the user rowid, or {@code null}
     * @param at      where
     * @param message the command line, or {@code null}
     * @return the rowid
     * @throws SQLException when inserting fails
     */
    public long command(long time, @Nullable Integer user, @NotNull Place at, @Nullable String message)
            throws SQLException {
        Map<String, Object> values = event(time, user, at);
        values.put("message", message);
        return insert("command", values);
    }

    /**
     * A {@code session} row.
     *
     * @param time   Unix seconds
     * @param user   the user rowid, or {@code null}
     * @param at     where
     * @param action 0 logout, 1 login
     * @return the rowid
     * @throws SQLException when inserting fails
     */
    public long session(long time, @Nullable Integer user, @NotNull Place at, int action) throws SQLException {
        Map<String, Object> values = event(time, user, at);
        values.put("action", action);
        return insert("session", values);
    }

    /**
     * An {@code entity_interaction} row.
     *
     * @param time   Unix seconds
     * @param user   the user rowid, or {@code null}
     * @param at     where
     * @param type   the {@code entity_map} id
     * @param action 0 click, 1 shear, 2 leash, 3 unleash
     * @return the rowid
     * @throws SQLException when inserting fails
     */
    public long entityInteraction(long time, @Nullable Integer user, @NotNull Place at, int type, int action)
            throws SQLException {
        Map<String, Object> values = event(time, user, at);
        values.putAll(values("entity_spawn_rowid", 0, "type", type, "action", action, "rolled_back", 0));
        return insert("entity_interaction", values);
    }

    /**
     * Leaves a gap in a table's rowids, as rows CoreProtect purged would.
     *
     * @param table the table without prefix
     * @param count how many rowids to skip
     */
    public void skipRowIds(@NotNull String table, long count) {
        rowIds.merge(table, count, Long::sum);
    }

    /**
     * Runs a statement, with {@code {prefix}} replaced by the table prefix (for rows the helpers cannot
     * make, such as NULLs in every column).
     *
     * @param sql the statement
     * @throws SQLException when it fails
     */
    public void execute(@NotNull String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql.replace("{prefix}", prefix));
        }
    }

    /**
     * Drops every table the fixture created (server engines share a database between runs).
     *
     * @throws SQLException when dropping fails
     */
    public void drop() throws SQLException {
        for (CoreProtectTables.Table table : CoreProtectTables.tables(layout)) {
            execute("DROP TABLE IF EXISTS " + quote(prefix + table.name()));
        }
    }

    @Override
    public void close() throws SQLException {
        if (owned) {
            connection.close();
        }
    }

    private static @NotNull CoreProtectFixture create(@NotNull SourceType engine, @NotNull Connection connection,
            boolean owned, @NotNull String prefix, @NotNull Layout layout, @Nullable Path file) throws SQLException {
        CoreProtectFixture fixture = new CoreProtectFixture(engine, connection, owned, prefix, layout, file);
        for (String sql : CoreProtectTables.ddl(engine, prefix, layout)) {
            fixture.execute(sql);
        }
        return fixture;
    }

    private int named(@NotNull String table, @NotNull String column, @NotNull String name) throws SQLException {
        int id = ids.merge(table, 1, Integer::sum);
        insert(table, values("id", id, column, name));
        return id;
    }

    private static @NotNull Map<String, Object> event(long time, @Nullable Integer user, @NotNull Place at) {
        return values("time", time, "user", user, "wid", at.world(), "x", at.x(), "y", at.y(), "z", at.z());
    }

    private @NotNull Map<String, Object> containerValues(long time, @Nullable Integer user, @NotNull Place at,
            int type, int amount, byte @Nullable [] metadata, int action, int rolledBack) {
        Map<String, Object> values = event(time, user, at);
        values.putAll(values("type", type, "data", 0, "amount", amount, "metadata", metadata, "action", action,
                "rolled_back", rolledBack));
        return values;
    }

    /** Inserts a row with the next rowid; columns the layout lacks are dropped. */
    private long insert(@NotNull String table, @NotNull Map<String, Object> values) throws SQLException {
        CoreProtectTables.Table spec = CoreProtectTables.table(layout, table);
        long rowId = rowIds.merge(table, 1L, Long::sum);
        List<String> columns = new ArrayList<>();
        List<String> placeholders = new ArrayList<>();
        List<Object> parameters = new ArrayList<>();
        columns.add("rowid");
        placeholders.add("?");
        parameters.add(rowId);
        for (Map.Entry<String, Object> value : values.entrySet()) {
            if (!spec.has(value.getKey())) {
                continue;
            }
            columns.add(quote(value.getKey()));
            if (value.getValue() == null) {
                placeholders.add("NULL");
            } else if (value.getValue() instanceof byte[] bytes && engine == SourceType.CLICKHOUSE) {
                placeholders.add("unhex(?)");
                parameters.add(HexFormat.of().formatHex(bytes));
            } else {
                placeholders.add("?");
                parameters.add(value.getValue());
            }
        }
        String sql = "INSERT INTO " + quote(prefix + table) + " (" + String.join(", ", columns) + ") VALUES ("
                + String.join(", ", placeholders) + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.size(); i++) {
                Object parameter = parameters.get(i);
                switch (parameter) {
                    case Long number -> statement.setLong(i + 1, number);
                    case Integer number -> statement.setInt(i + 1, number);
                    case String text -> statement.setString(i + 1, text);
                    case byte[] bytes -> statement.setBytes(i + 1, bytes);
                    default -> throw new IllegalArgumentException("unsupported value " + parameter);
                }
            }
            statement.executeUpdate();
        }
        return rowId;
    }

    private @NotNull String quote(@NotNull String identifier) {
        char quote = engine == SourceType.MYSQL || engine == SourceType.CLICKHOUSE ? '`' : '"';
        return quote + identifier + quote;
    }

    private static @NotNull Map<String, Object> values(@NotNull Object... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            values.put((String) pairs[i], pairs[i + 1]);
        }
        return values;
    }
}
