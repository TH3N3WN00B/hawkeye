package gg.moonrise.hawkeye.core.sql;

import gg.moonrise.hawkeye.config.file.StorageSettings;
import gg.moonrise.hawkeye.core.repository.ActorRepository;
import gg.moonrise.hawkeye.core.repository.DictionaryRepository;
import gg.moonrise.hawkeye.core.repository.ServerRepository;
import gg.moonrise.hawkeye.core.storage.Dictionaries;
import gg.moonrise.hawkeye.core.storage.Shard;
import gg.moonrise.hawkeye.entity.server.ServerNode;
import java.nio.file.Path;
import java.sql.SQLException;
import org.jetbrains.annotations.NotNull;

/**
 * A migrated SQLite shard in a temporary folder, for repository and store tests. SQLite is the only
 * engine available without a server; the MySQL and PostgreSQL dialects run the same statements in
 * the opt-in integration tests.
 */
public final class SqliteHarness implements AutoCloseable {

    private final Database database;
    private final Shard shard;

    private SqliteHarness(@NotNull Database database) {
        this.database = database;
        this.shard = new Shard("home", database, new Dictionaries(new DictionaryRepository(), new ActorRepository(),
                new ServerRepository()), true);
    }

    /**
     * Opens and migrates a database file in a folder.
     *
     * @param folder the folder (a JUnit {@code @TempDir})
     * @return the harness
     * @throws SQLException when migrating fails
     */
    public static @NotNull SqliteHarness open(@NotNull Path folder) throws SQLException {
        Database database = new DatabaseFactory().open("home", new StorageSettings().withSqliteFile("hawkeye.db"),
                folder);
        database.read(connection -> Migrations.apply(connection, database.dialect(), database.tables()));
        return new SqliteHarness(database);
    }

    /**
     * The database.
     *
     * @return the database
     */
    public @NotNull Database database() {
        return database;
    }

    /**
     * The database as a home shard with a fresh dictionary cache.
     *
     * @return the shard
     */
    public @NotNull Shard shard() {
        return shard;
    }

    /**
     * A second shard object over the same database with its own empty cache: what another server
     * sharing the database sees.
     *
     * @return the shard
     */
    public @NotNull Shard otherServerView() {
        return new Shard("home", database, new Dictionaries(new DictionaryRepository(), new ActorRepository(),
                new ServerRepository()), true);
    }

    /**
     * Registers a server.
     *
     * @param name  the server name
     * @param scope its world scope
     * @return its node
     * @throws SQLException when registration fails
     */
    public @NotNull ServerNode register(@NotNull String name, @NotNull String scope) throws SQLException {
        return database.read(connection -> new ServerRepository().register(connection, database.sql(), name, scope,
                System.currentTimeMillis()));
    }

    @Override
    public void close() {
        database.close();
    }
}
