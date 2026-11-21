package gg.moonrise.hawkeye.core.storage.importer;

import gg.moonrise.hawkeye.core.repository.importer.ImportStateRepository;
import gg.moonrise.hawkeye.core.repository.network.ServerRepository;
import gg.moonrise.hawkeye.core.sql.Database;
import gg.moonrise.hawkeye.core.sql.Sql;
import gg.moonrise.hawkeye.core.storage.event.EventStore;
import gg.moonrise.hawkeye.core.storage.shard.Shard;
import gg.moonrise.hawkeye.entity.event.PendingEvent;
import gg.moonrise.hawkeye.entity.importer.CheckpointResult;
import gg.moonrise.hawkeye.entity.importer.ImportState;
import gg.moonrise.hawkeye.entity.server.ServerNode;
import gg.moonrise.hawkeye.util.EventIds;
import gg.moonrise.moss.spring.SpringComponent;
import java.io.Serial;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

/**
 * An import's storage on the home shard: its checkpoint ({@code import_state}), its import node (a
 * {@code server} row with {@code last_seen = 0}) and the checkpointed write of one segment. The segment
 * and the checkpoint are committed in one transaction, the checkpoint by compare-and-set on its
 * revision, so a resumed run continues exactly where the last commit ended and a second run of the
 * same source is stopped ({@link CheckpointResult.Superseded}) instead of importing rows twice.
 */
@SpringComponent
@RequiredArgsConstructor
public final class ImportStore {

    /** What the import node's name adds to the server name: {@code survival-coreprotect}, {@code -2}, ... */
    public static final String NODE_SUFFIX = "-coreprotect";

    private final @NotNull ImportStateRepository stateRepository;
    private final @NotNull ServerRepository serverRepository;
    private final @NotNull EventStore eventStore;

    /**
     * A source's stored checkpoint.
     *
     * @param home   the home shard
     * @param source the source key
     * @return the state, or empty when the source was never imported into this shard
     * @throws SQLException when reading fails
     */
    public @NotNull Optional<ImportState> load(@NotNull Shard home, @NotNull String source) throws SQLException {
        Database database = home.database();
        return database.read(connection -> stateRepository.find(connection, database.sql(), source));
    }

    /**
     * The stored checkpoint of a source, created when the source is new: a fresh import node is
     * registered (the first free name of {@code <serverName>-coreprotect}, {@code -coreprotect-2}, ...)
     * and a state at revision 1 is stored naming it. When another server creates the state at the same
     * moment, its state is returned and the node registered here stays unused.
     *
     * @param home       the home shard
     * @param source     the source key
     * @param serverName this server's name
     * @param scope      the world scope the imported events are stored under
     * @param order      the source's scan order ({@code rowid} or {@code time})
     * @param now        the current time, Unix milliseconds
     * @return the stored state
     * @throws SQLException when reading or writing fails, or the shard has no node id or name left
     */
    public @NotNull ImportState begin(@NotNull Shard home, @NotNull String source, @NotNull String serverName,
            @NotNull String scope, @NotNull String order, long now) throws SQLException {
        Optional<ImportState> existing = load(home, source);
        if (existing.isPresent()) {
            return existing.get();
        }
        Database database = home.database();
        Sql sql = database.sql();
        ServerNode node = registerNode(home, serverName, scope);
        ImportState fresh = ImportState.fresh(node.name(), order, now);
        boolean stored = database.read(connection -> stateRepository.insert(connection, sql, source, fresh));
        if (stored) {
            return fresh;
        }
        return load(home, source).orElseThrow(() -> new SQLException("the import state of " + source
                + " vanished right after another server stored it"));
    }

    /**
     * The import node a state names.
     *
     * @param home  the home shard
     * @param state the state
     * @return the node
     * @throws SQLException when reading fails or the node no longer exists
     */
    public @NotNull ServerNode node(@NotNull Shard home, @NotNull ImportState state) throws SQLException {
        Database database = home.database();
        return database.read(connection -> serverRepository.byName(connection, database.sql(), state.node()))
                .orElseThrow(() -> new SQLException("the import node " + state.node() + " is not registered"));
    }

    /**
     * Writes one segment of imported events and the checkpoint that accounts for it, in one
     * transaction ({@link EventStore#writeImported}). When the checkpoint is no longer at
     * {@code next.revision()}, the segment is rolled back with it.
     *
     * @param home     the home shard
     * @param node     the import node
     * @param scope    the world scope the events are stored under
     * @param events   the events, with final stamps
     * @param executor where the segment's frames are compressed
     * @param source   the source key
     * @param next     the checkpoint after these events; its revision is the one the caller holds
     * @return the stored state at the new revision, or superseded when another run moved the checkpoint
     * @throws SQLException when interning or writing fails
     */
    public @NotNull CheckpointResult write(@NotNull Shard home, @NotNull ServerNode node, @NotNull String scope,
            @NotNull List<PendingEvent> events, @NotNull Executor executor, @NotNull String source,
            @NotNull ImportState next) throws SQLException {
        Sql sql = home.database().sql();
        try {
            eventStore.writeImported(home, node, scope, events, executor, connection -> {
                if (!stateRepository.compareAndSet(connection, sql, source, next)) {
                    throw new SupersededException(source);
                }
                return null;
            });
        } catch (SupersededException e) {
            return new CheckpointResult.Superseded();
        }
        return new CheckpointResult.Saved(next.withRevision(next.revision() + 1, next.updatedMillis()));
    }

    /**
     * Writes the checkpoint alone: a batch that mapped to no event, or the final "complete" mark.
     *
     * @param home   the home shard
     * @param source the source key
     * @param next   the new checkpoint; its revision is the one the caller holds
     * @return the stored state at the new revision, or superseded when another run moved the checkpoint
     * @throws SQLException when writing fails
     */
    public @NotNull CheckpointResult save(@NotNull Shard home, @NotNull String source, @NotNull ImportState next)
            throws SQLException {
        Database database = home.database();
        boolean saved = database.read(connection -> stateRepository.compareAndSet(connection, database.sql(), source,
                next));
        return saved ? new CheckpointResult.Saved(next.withRevision(next.revision() + 1, next.updatedMillis()))
                : new CheckpointResult.Superseded();
    }

    private @NotNull ServerNode registerNode(@NotNull Shard home, @NotNull String serverName, @NotNull String scope)
            throws SQLException {
        Database database = home.database();
        for (int attempt = 1; attempt <= EventIds.MAX_NODE; attempt++) {
            String name = serverName + NODE_SUFFIX + (attempt == 1 ? "" : "-" + attempt);
            Optional<ServerNode> node = database.read(connection -> serverRepository.registerImport(connection,
                    database.sql(), name, scope));
            if (node.isPresent()) {
                return node.get();
            }
        }
        throw new SQLException("no free import node name for " + serverName);
    }

    /** The checkpoint moved under this run: thrown inside the transaction so the segment rolls back. */
    private static final class SupersededException extends SQLException {

        @Serial
        private static final long serialVersionUID = 1L;

        private SupersededException(@NotNull String source) {
            super("the import checkpoint of " + source + " was moved by another run");
        }
    }
}
