package gg.moonrise.hawkeye.core.repository.coreprotect;

import static gg.moonrise.hawkeye.core.repository.coreprotect.CoreProtectFixture.at;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.entity.importer.SourceCursor;
import gg.moonrise.hawkeye.entity.importer.SourceNames;
import gg.moonrise.hawkeye.entity.importer.SourceRow;
import gg.moonrise.hawkeye.entity.importer.SourceTable;
import gg.moonrise.hawkeye.entity.importer.SourceUser;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * One reader scenario for every engine: {@link #populate} writes a CoreProtect database with every row
 * type, NULLs, a large rowid gap and a time gap of months, and {@link #verify} reads it back through a
 * {@link CoreProtectSource} in small pages. Times rise with the rowids, so rowid order and ClickHouse's
 * time order read the same rows in the same order.
 */
public final class ReaderScenario {

    /** CoreProtect's install time in the scenario: 2026-01-01T00:00:00Z. */
    public static final long INSTALLED = 1_767_225_600L;

    /** The rowid gap in {@code block}, as a purge leaves. */
    public static final long GAP = 5_000_000L;

    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID ZOMBIE = UUID.fromString("00000000-0000-0000-0000-0000000000ee");
    private static final byte[] METADATA = {(byte) 0xac, (byte) 0xed, 0, 5, 1, 2, 3, (byte) 0xff, 0};

    private ReaderScenario() {
    }

    /**
     * What the scenario wrote.
     *
     * @param rows  per table, the rows described by {@link #describe}, in scan order
     * @param names the dictionaries
     */
    public record Expected(@NotNull Map<SourceTable, List<String>> rows, @NotNull SourceNames names) {
    }

    /**
     * Writes the scenario's rows.
     *
     * @param db a fixture with the {@link CoreProtectFixture.Layout#CURRENT} layout
     * @return what a reader must find
     * @throws SQLException when writing fails
     */
    public static @NotNull Expected populate(@NotNull CoreProtectFixture db) throws SQLException {
        Map<SourceTable, List<String>> rows = new EnumMap<>(SourceTable.class);
        for (SourceTable table : SourceTable.values()) {
            rows.put(table, new ArrayList<>());
        }
        db.version(INSTALLED, "25.0");
        db.version(INSTALLED + 100_000, "25.1");
        int alex = db.user("Alex", ALEX.toString());
        int tnt = db.user("#tnt", null);
        int blank = db.user("Blank", " ");
        int odd = db.user("Odd", "not-a-uuid");
        int world = db.world("world");
        int nether = db.world("world_nether");
        int stone = db.material("minecraft:stone");
        int stairs = db.material("minecraft:oak_stairs");
        int east = db.blockData("facing=east");
        int bottom = db.blockData("half=bottom");
        int zombie = db.entity("zombie");
        int cow = db.entity("cow");
        long spawn = db.entitySpawn(ZOMBIE.toString());
        long t = INSTALLED + 10;

        long row = db.block(t, alex, at(world, 10, 64, -20), stairs, 0, east + "," + bottom + ",x,999", 0, 1);
        add(rows, block(row, t, alex, world, 10, 64, -20, stairs, 0, new int[] {east, bottom, 999}, 0, 1, null));
        row = db.block(++t, tnt, at(nether, -1, -64, 3), stone, 0, null, 1, 0);
        add(rows, block(row, t, tnt, nether, -1, -64, 3, stone, 0, new int[0], 1, 0, null));
        row = db.block(++t, alex, at(world, 5, 70, 5), zombie, spawn, null, 13, 0);
        add(rows, block(row, t, alex, world, 5, 70, 5, zombie, spawn, new int[0], 13, 0, ZOMBIE));
        row = db.block(++t, alex, at(world, 6, 70, 5), cow, 404, null, 13, 0);
        add(rows, block(row, t, alex, world, 6, 70, 5, cow, 404, new int[0], 13, 0, null));
        row = db.block(++t, alex, at(world, 7, 70, 5), 0, odd, null, 3, 3);
        add(rows, block(row, t, alex, world, 7, 70, 5, 0, odd, new int[0], 3, 3, null));
        row = db.block(++t, null, at(null, 0, 0, 0), 0, 0, null, 0, 0);
        add(rows, block(row, t, 0, SourceRow.NO_WORLD, 0, 0, 0, 0, 0, new int[0], 0, 0, null));
        db.skipRowIds("block", GAP);
        long later = t + 90L * 24 * 60 * 60;
        row = db.block(later, blank, at(world, 1, 2, 3), stone, 0, String.valueOf(east), 1, 0);
        add(rows, block(row, later, blank, world, 1, 2, 3, stone, 0, new int[] {east}, 1, 0, null));

        row = db.container(++t, alex, at(world, 1, 64, 1), stone, 12, METADATA, 0, 1);
        add(rows, new SourceRow.Container(SourceTable.CONTAINER, row, t, alex, world, 1, 64, 1, stone, 12, METADATA, 0,
                1));
        row = db.container(++t, alex, at(world, 1, 64, 1), stone, 3, null, 1, 0);
        add(rows, new SourceRow.Container(SourceTable.CONTAINER, row, t, alex, world, 1, 64, 1, stone, 3, null, 1, 0));
        row = db.entityContainer(++t, alex, spawn, at(world, 2, 65, 2), stairs, 1, METADATA, 1, 0);
        add(rows, new SourceRow.Container(SourceTable.ENTITY_CONTAINER, row, t, alex, world, 2, 65, 2, stairs, 1,
                METADATA, 1, 0));
        row = db.item(++t, alex, at(world, 3, 66, 3), stone, 64, METADATA, 2);
        add(rows, new SourceRow.Item(row, t, alex, world, 3, 66, 3, stone, 64, METADATA, 2));
        row = db.item(++t, alex, at(world, 3, 66, 3), stone, 1, null, 10);
        add(rows, new SourceRow.Item(row, t, alex, world, 3, 66, 3, stone, 1, null, 10));
        row = db.sign(++t, alex, at(world, 4, 67, 4), 1, 0, Arrays.asList("§aHello", "world", null, "", "back",
                null, null, "end"), 0);
        add(rows, new SourceRow.Sign(row, t, alex, world, 4, 67, 4, 1, 0, List.of("§aHello", "world", "", "", "back",
                "", "", "end"), 0));
        row = db.sign(++t, alex, at(world, 4, 67, 4), 2, 1, List.of(), 1);
        add(rows, new SourceRow.Sign(row, t, alex, world, 4, 67, 4, 2, 1, List.of("", "", "", "", "", "", "", ""), 1));
        row = db.chat(++t, alex, at(world, 0, 64, 0), "hi there ✓");
        add(rows, new SourceRow.Message(SourceTable.CHAT, row, t, alex, world, 0, 64, 0, "hi there ✓"));
        row = db.chat(++t, alex, at(world, 0, 64, 0), null);
        add(rows, new SourceRow.Message(SourceTable.CHAT, row, t, alex, world, 0, 64, 0, ""));
        row = db.command(++t, alex, at(nether, 0, 64, 0), "/tp 0 64 0");
        add(rows, new SourceRow.Message(SourceTable.COMMAND, row, t, alex, nether, 0, 64, 0, "/tp 0 64 0"));
        row = db.session(++t, alex, at(world, 0, 64, 0), 1);
        add(rows, new SourceRow.Session(row, t, alex, world, 0, 64, 0, 1));
        row = db.session(++t, alex, at(world, 0, 64, 0), 0);
        add(rows, new SourceRow.Session(row, t, alex, world, 0, 64, 0, 0));
        row = db.entityInteraction(++t, alex, at(world, 8, 64, 8), cow, 1);
        add(rows, new SourceRow.EntityInteraction(row, t, alex, world, 8, 64, 8, cow, 1));

        SourceNames names = new SourceNames(
                Map.of(alex, new SourceUser("Alex", ALEX), tnt, new SourceUser("#tnt", null), blank,
                        new SourceUser("Blank", null), odd, new SourceUser("Odd", null)),
                Map.of(world, "world", nether, "world_nether"),
                Map.of(stone, "minecraft:stone", stairs, "minecraft:oak_stairs"),
                Map.of(east, "facing=east", bottom, "half=bottom"),
                Map.of(zombie, "zombie", cow, "cow"));
        return new Expected(rows, names);
    }

    /**
     * Reads the scenario back and checks every row, the dictionaries, the fingerprint, the remaining
     * estimates and resuming from a cursor.
     *
     * @param source   the open source
     * @param expected what {@link #populate} wrote
     * @throws SQLException when reading fails
     */
    public static void verify(@NotNull CoreProtectSource source, @NotNull Expected expected) throws SQLException {
        assertEquals(EnumSet.allOf(SourceTable.class), source.tables());
        assertEquals(INSTALLED + ":25.0", source.fingerprint());
        assertFalse(source.migrating());
        assertEquals(expected.names(), source.names());
        for (SourceTable table : SourceTable.values()) {
            List<String> rows = new ArrayList<>();
            SourceCursor cursor = SourceCursor.START;
            assertTrue(source.remaining(table, cursor) >= expected.rows().get(table).size(), table.name());
            while (true) {
                List<SourceRow> page = source.read(table, cursor, 2);
                if (page.isEmpty()) {
                    break;
                }
                assertTrue(page.size() <= 2);
                page.forEach(row -> rows.add(describe(row)));
                cursor = page.getLast().cursor();
            }
            assertEquals(expected.rows().get(table), rows, table.name());
            assertEquals(0, source.remaining(table, cursor), table.name() + " after the last row");
        }
        // Resuming after the second block row reads the rest, across the gap.
        List<String> blocks = expected.rows().get(SourceTable.BLOCK);
        SourceRow second = source.read(SourceTable.BLOCK, SourceCursor.START, 2).getLast();
        List<String> rest = new ArrayList<>();
        SourceCursor cursor = second.cursor();
        for (List<SourceRow> page = source.read(SourceTable.BLOCK, cursor, 100); !page.isEmpty();
                page = source.read(SourceTable.BLOCK, cursor, 100)) {
            page.forEach(row -> rest.add(describe(row)));
            cursor = page.getLast().cursor();
        }
        assertEquals(blocks.subList(2, blocks.size()), rest);
    }

    /**
     * A row as comparable text, arrays included by value.
     *
     * @param row the row
     * @return the text
     */
    public static @NotNull String describe(@NotNull SourceRow row) {
        return switch (row) {
            case SourceRow.Block block -> block.toString().replaceAll("blockData=\\[I@[0-9a-f]+",
                    "blockData=" + Arrays.toString(block.blockData()));
            case SourceRow.Container container -> container.toString().replaceAll("metadata=\\[B@[0-9a-f]+",
                    "metadata=" + Arrays.toString(container.metadata()));
            case SourceRow.Item item -> item.toString().replaceAll("metadata=\\[B@[0-9a-f]+",
                    "metadata=" + Arrays.toString(item.metadata()));
            default -> row.toString();
        };
    }

    private static @NotNull SourceRow.Block block(long rowId, long time, int user, int world, int x, int y, int z,
            int type, long data, int @NotNull [] blockData, int action, int rolledBack, UUID entity) {
        return new SourceRow.Block(rowId, time, user, world, x, y, z, type, data, blockData, action, rolledBack,
                entity);
    }

    private static void add(@NotNull Map<SourceTable, List<String>> rows, @NotNull SourceRow row) {
        rows.get(row.table()).add(describe(row));
    }
}
