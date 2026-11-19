package gg.moonrise.hawkeye.core.repository.dictionary;

import gg.moonrise.hawkeye.core.sql.Dialect;
import gg.moonrise.hawkeye.core.sql.Sql;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.hawkeye.entity.actor.ActorKind;
import gg.moonrise.moss.spring.SpringComponent;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jetbrains.annotations.NotNull;

/**
 * The {@code actor} dictionary. A player is keyed by UUID and keeps the latest name it was seen with;
 * a source is keyed by its {@code #name}.
 */
@SpringComponent
public final class ActorRepository {

    /** Actors per statement. */
    public static final int ACTORS_PER_STATEMENT = 250;

    private static final List<String> COLUMNS = List.of("kind", "actor_key", "name", "name_lower");

    /**
     * The ids of actors, creating missing ones and updating changed names. Existing actors are selected
     * first and only the missing ones are inserted (PostgreSQL identities and InnoDB auto-increments
     * consume an id even for a conflicting insert).
     *
     * @param connection a connection in auto-commit mode
     * @param sql        the shard's SQL context
     * @param actors     the actors
     * @return actor key to id, for every actor
     * @throws SQLException when a statement fails
     */
    public @NotNull Map<String, Integer> intern(@NotNull Connection connection, @NotNull Sql sql,
            @NotNull Collection<Actor> actors) throws SQLException {
        Map<String, Actor> byKey = new LinkedHashMap<>();
        actors.forEach(actor -> byKey.put(actor.key(), actor));
        Map<String, StoredActor> existing = byKeys(connection, sql, byKey.keySet());
        Map<String, Integer> ids = new HashMap<>();
        List<Actor> missing = new ArrayList<>();
        for (Actor actor : byKey.values()) {
            StoredActor stored = existing.get(actor.key());
            if (stored == null) {
                missing.add(actor);
                continue;
            }
            ids.put(actor.key(), stored.id());
            if (!stored.name().equals(actor.name())) {
                rename(connection, sql, stored.id(), actor.name());
            }
        }
        for (int from = 0; from < missing.size(); from += ACTORS_PER_STATEMENT) {
            List<Actor> chunk = missing.subList(from, Math.min(missing.size(), from + ACTORS_PER_STATEMENT));
            String insert = sql.dialect().insertIgnoringDuplicates(sql.tables().actor(), COLUMNS, chunk.size());
            try (PreparedStatement statement = connection.prepareStatement(insert)) {
                int index = 1;
                for (Actor actor : chunk) {
                    statement.setInt(index++, actor.kind().code());
                    statement.setString(index++, actor.key());
                    statement.setString(index++, actor.name());
                    statement.setString(index++, actor.name().toLowerCase(Locale.ROOT));
                }
                statement.executeUpdate();
            }
        }
        if (!missing.isEmpty()) {
            byKeys(connection, sql, missing.stream().map(Actor::key).toList())
                    .forEach((key, stored) -> ids.put(key, stored.id()));
        }
        return ids;
    }

    private @NotNull Map<String, StoredActor> byKeys(@NotNull Connection connection, @NotNull Sql sql,
            @NotNull Collection<String> keys) throws SQLException {
        List<String> values = List.copyOf(keys);
        Map<String, StoredActor> stored = new HashMap<>();
        for (int from = 0; from < values.size(); from += ACTORS_PER_STATEMENT) {
            List<String> chunk = values.subList(from, Math.min(values.size(), from + ACTORS_PER_STATEMENT));
            String select = "SELECT actor_key, id, name FROM " + sql.tables().actor() + " WHERE actor_key IN ("
                    + Dialect.placeholders(chunk.size()) + ")";
            try (PreparedStatement statement = connection.prepareStatement(select)) {
                for (int i = 0; i < chunk.size(); i++) {
                    statement.setString(i + 1, chunk.get(i));
                }
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        stored.put(rows.getString(1), new StoredActor(rows.getInt(2), rows.getString(3)));
                    }
                }
            }
        }
        return stored;
    }

    private static void rename(@NotNull Connection connection, @NotNull Sql sql, int id, @NotNull String name)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE " + sql.tables().actor() + " SET name = ?, name_lower = ? WHERE id = ?")) {
            statement.setString(1, name);
            statement.setString(2, name.toLowerCase(Locale.ROOT));
            statement.setInt(3, id);
            statement.executeUpdate();
        }
    }

    /**
     * Actors by id.
     *
     * @param connection a connection
     * @param sql        the shard's SQL context
     * @param ids        actor ids
     * @return id to actor, for the ids that exist
     * @throws SQLException when a query fails
     */
    public @NotNull Map<Integer, Actor> byIds(@NotNull Connection connection, @NotNull Sql sql,
            @NotNull Collection<Integer> ids) throws SQLException {
        List<Integer> values = List.copyOf(ids);
        Map<Integer, Actor> actors = new HashMap<>();
        for (int from = 0; from < values.size(); from += ACTORS_PER_STATEMENT) {
            List<Integer> chunk = values.subList(from, Math.min(values.size(), from + ACTORS_PER_STATEMENT));
            String select = "SELECT id, kind, actor_key, name FROM " + sql.tables().actor() + " WHERE id IN ("
                    + Dialect.placeholders(chunk.size()) + ")";
            try (PreparedStatement statement = connection.prepareStatement(select)) {
                for (int i = 0; i < chunk.size(); i++) {
                    statement.setInt(i + 1, chunk.get(i));
                }
                readInto(statement, actors);
            }
        }
        return actors;
    }

    /**
     * Actors whose current name (players) or {@code #name} (sources) matches, ignoring case. A name can
     * match several players when it changed hands.
     *
     * @param connection a connection
     * @param sql        the shard's SQL context
     * @param names      names or {@code #sources}
     * @return id to actor
     * @throws SQLException when a query fails
     */
    public @NotNull Map<Integer, Actor> byNames(@NotNull Connection connection, @NotNull Sql sql,
            @NotNull Collection<String> names) throws SQLException {
        List<String> values = names.stream().map(name -> name.toLowerCase(Locale.ROOT)).distinct().toList();
        Map<Integer, Actor> actors = new HashMap<>();
        for (int from = 0; from < values.size(); from += ACTORS_PER_STATEMENT) {
            List<String> chunk = values.subList(from, Math.min(values.size(), from + ACTORS_PER_STATEMENT));
            String select = "SELECT id, kind, actor_key, name FROM " + sql.tables().actor() + " WHERE name_lower IN ("
                    + Dialect.placeholders(chunk.size()) + ")";
            try (PreparedStatement statement = connection.prepareStatement(select)) {
                for (int i = 0; i < chunk.size(); i++) {
                    statement.setString(i + 1, chunk.get(i));
                }
                readInto(statement, actors);
            }
        }
        return actors;
    }

    private static void readInto(@NotNull PreparedStatement statement, @NotNull Map<Integer, Actor> actors)
            throws SQLException {
        try (ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                ActorKind kind = ActorKind.byCode(rows.getInt(2)).orElse(ActorKind.SOURCE);
                actors.put(rows.getInt(1), new Actor(kind, rows.getString(3), rows.getString(4)));
            }
        }
    }

    private record StoredActor(int id, @NotNull String name) {
    }
}
