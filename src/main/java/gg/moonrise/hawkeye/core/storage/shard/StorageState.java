package gg.moonrise.hawkeye.core.storage.shard;

import gg.moonrise.hawkeye.entity.server.ServerNode;
import org.jetbrains.annotations.NotNull;

/** The home shard's connection state, published by {@link StorageManager}. */
public sealed interface StorageState {

    /** The pool is open and the schema and registration are in progress (or being retried). */
    record Connecting() implements StorageState {
    }

    /**
     * Migrated and registered: the writer may write.
     *
     * @param home the home shard
     * @param node this server's registration
     */
    record Ready(@NotNull Shard home, @NotNull ServerNode node) implements StorageState {
    }

    /**
     * The last attempt failed; another follows after a delay.
     *
     * @param reason what went wrong
     */
    record Failed(@NotNull String reason) implements StorageState {
    }

    /**
     * The database was written by a newer Hawkeye; this build stays read-only until it is updated.
     *
     * @param version the database's schema version
     */
    record Incompatible(int version) implements StorageState {
    }
}
