package gg.moonrise.hawkeye.core.repository.coreprotect;

import gg.moonrise.hawkeye.entity.importer.SourceRow;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Gives the entity-spawn rows of a page ({@code block} action 13, whose {@code data} is an
 * {@code entity_spawn} rowid) the spawned entity's UUID: one {@code IN (...)} query per page. Without an
 * {@code entity_spawn} table (an older CoreProtect) the rows stay without one.
 */
public final class SpawnUuids {

    /** The block action of an entity spawn. */
    public static final int SPAWN_ACTION = 13;

    private static final String TABLE = "entity_spawn";
    private static final int PER_STATEMENT = 500;

    private final @NotNull Connection connection;
    private final @NotNull SourceSchema schema;

    /**
     * A resolver.
     *
     * @param connection the source connection
     * @param schema     its schema
     */
    public SpawnUuids(@NotNull Connection connection, @NotNull SourceSchema schema) {
        this.connection = connection;
        this.schema = schema;
    }

    /**
     * The page with UUIDs filled in where they are known.
     *
     * @param rows a page of {@code block} rows
     * @return the same rows, spawn rows replaced by copies carrying their UUID
     * @throws SQLException when reading fails
     */
    public @NotNull List<SourceRow> resolve(@NotNull List<SourceRow> rows) throws SQLException {
        Set<Long> spawns = new LinkedHashSet<>();
        for (SourceRow row : rows) {
            if (row instanceof SourceRow.Block block && block.action() == SPAWN_ACTION && block.data() > 0) {
                spawns.add(block.data());
            }
        }
        if (spawns.isEmpty() || !schema.has(TABLE, "uuid")) {
            return rows;
        }
        Map<Long, UUID> uuids = uuids(List.copyOf(spawns));
        List<SourceRow> out = new ArrayList<>(rows.size());
        for (SourceRow row : rows) {
            if (row instanceof SourceRow.Block block && block.action() == SPAWN_ACTION
                    && uuids.containsKey(block.data())) {
                out.add(new SourceRow.Block(block.rowId(), block.time(), block.user(), block.world(), block.x(),
                        block.y(), block.z(), block.type(), block.data(), block.blockData(), block.action(),
                        block.rolledBack(), uuids.get(block.data())));
            } else {
                out.add(row);
            }
        }
        return out;
    }

    private @NotNull Map<Long, UUID> uuids(@NotNull List<Long> ids) throws SQLException {
        Map<Long, UUID> uuids = new HashMap<>();
        for (int from = 0; from < ids.size(); from += PER_STATEMENT) {
            List<Long> chunk = ids.subList(from, Math.min(ids.size(), from + PER_STATEMENT));
            String sql = "SELECT rowid, " + schema.dialect().quote("uuid") + " FROM " + schema.table(TABLE)
                    + " WHERE rowid IN (" + String.join(", ", Collections.nCopies(chunk.size(), "?")) + ")";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (int i = 0; i < chunk.size(); i++) {
                    statement.setLong(i + 1, chunk.get(i));
                }
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        long id = rows.getLong(1);
                        SourceNamesReader.uuid(rows.getString(2)).ifPresent(uuid -> uuids.put(id, uuid));
                    }
                }
            }
        }
        return uuids;
    }
}
