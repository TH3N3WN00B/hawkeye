package gg.moonrise.hawkeye.listener;

import gg.moonrise.hawkeye.core.storage.EventStore;
import gg.moonrise.hawkeye.core.storage.ShardScope;
import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.entity.query.Area;
import gg.moonrise.hawkeye.entity.query.LookupQuery;
import gg.moonrise.hawkeye.support.TestPlugins;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.jetbrains.annotations.NotNull;

/** Reads back what the listeners recorded: flushes the queue, then reads the home shard oldest first. */
public final class Recorded {

    private Recorded() {
    }

    /**
     * Everything recorded so far.
     *
     * @return the events, oldest first
     */
    public static @NotNull List<StoredEvent> events() {
        TestPlugins.flush();
        try {
            return TestPlugins.bean(EventStore.class).find(TestPlugins.awaitStorage().home(),
                    LookupQuery.at(new Area.Everywhere()), new ShardScope.Scopes(Set.of("server")), 0, 1000, false);
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the events", e);
        }
    }

    /**
     * The events of some actions.
     *
     * @param actions the actions
     * @return the events, oldest first
     */
    public static @NotNull List<StoredEvent> events(@NotNull Action @NotNull ... actions) {
        Set<Action> wanted = Set.of(actions);
        return events().stream().filter(event -> wanted.contains(event.action())).toList();
    }

    /**
     * The events of some actions ordered by x, oldest first at each x. The writer ranks a second's
     * events by position, so stored order across positions is not capture order; at one position it
     * still is, and the sort is stable.
     *
     * @param actions the actions
     * @return the events
     */
    public static @NotNull List<StoredEvent> byX(@NotNull Action @NotNull ... actions) {
        return events(actions).stream().sorted(Comparator.comparingInt(StoredEvent::x)).toList();
    }

    /**
     * The only event of an action.
     *
     * @param action the action
     * @return the event
     * @throws AssertionError when there is not exactly one
     */
    public static @NotNull StoredEvent only(@NotNull Action action) {
        List<StoredEvent> events = events(action);
        if (events.size() != 1) {
            throw new AssertionError("expected one " + action + " event, found " + events);
        }
        return events.getFirst();
    }
}
