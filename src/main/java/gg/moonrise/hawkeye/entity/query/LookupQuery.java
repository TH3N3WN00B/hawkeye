package gg.moonrise.hawkeye.entity.query;

import gg.moonrise.hawkeye.entity.action.Action;
import java.util.EnumSet;
import java.util.Set;
import org.jetbrains.annotations.NotNull;

/**
 * What to read: the filter shared by lookups, the inspector, rollbacks, restores and purges. An empty
 * set means "no filter" for that dimension.
 *
 * @param servers        which servers' events
 * @param area           where
 * @param time           when
 * @param actors         who (player names or {@code #sources}, lower case)
 * @param excludedActors who not
 * @param actions        what happened
 * @param includedTypes  namespaced materials or entity types to keep ({@code minecraft:stone})
 * @param excludedTypes  namespaced materials or entity types to drop
 * @param rollbackState  which events by their rolled-back flag
 */
public record LookupQuery(
        @NotNull ServerScope servers,
        @NotNull Area area,
        @NotNull TimeWindow time,
        @NotNull Set<String> actors,
        @NotNull Set<String> excludedActors,
        @NotNull Set<Action> actions,
        @NotNull Set<String> includedTypes,
        @NotNull Set<String> excludedTypes,
        @NotNull RollbackState rollbackState) {

    /**
     * Copies the sets.
     *
     * @param servers        which servers' events
     * @param area           where
     * @param time           when
     * @param actors         who
     * @param excludedActors who not
     * @param actions        what happened
     * @param includedTypes  types to keep
     * @param excludedTypes  types to drop
     * @param rollbackState  which events by their flag
     */
    public LookupQuery {
        actors = Set.copyOf(actors);
        excludedActors = Set.copyOf(excludedActors);
        actions = actions.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(actions));
        includedTypes = Set.copyOf(includedTypes);
        excludedTypes = Set.copyOf(excludedTypes);
    }

    /**
     * A query with no filter but the area.
     *
     * @param area where
     * @return the query, on this server, over all time
     */
    public static @NotNull LookupQuery at(@NotNull Area area) {
        return new LookupQuery(new ServerScope.Local(), area, TimeWindow.ALL, Set.of(), Set.of(), Set.of(), Set.of(),
                Set.of(), RollbackState.ANY);
    }

    /**
     * This query with another rollback-state filter.
     *
     * @param state the filter
     * @return the copy
     */
    public @NotNull LookupQuery withRollbackState(@NotNull RollbackState state) {
        return new LookupQuery(servers, area, time, actors, excludedActors, actions, includedTypes, excludedTypes,
                state);
    }

    /**
     * This query with another action filter.
     *
     * @param filter the actions
     * @return the copy
     */
    public @NotNull LookupQuery withActions(@NotNull Set<Action> filter) {
        return new LookupQuery(servers, area, time, actors, excludedActors, filter, includedTypes, excludedTypes,
                rollbackState);
    }

    /**
     * This query with another time window.
     *
     * @param window the window
     * @return the copy
     */
    public @NotNull LookupQuery withTime(@NotNull TimeWindow window) {
        return new LookupQuery(servers, area, window, actors, excludedActors, actions, includedTypes, excludedTypes,
                rollbackState);
    }
}
