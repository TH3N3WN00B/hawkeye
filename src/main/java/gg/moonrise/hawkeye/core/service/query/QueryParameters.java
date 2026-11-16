package gg.moonrise.hawkeye.core.service.query;

import gg.moonrise.hawkeye.entity.action.Action;
import java.util.Set;
import org.jetbrains.annotations.NotNull;

/**
 * A parsed parameter list ({@code u:Alex t:2h r:10 a:-block i:diamond_ore}), before the sender's
 * position and the command's defaults turn it into a {@code LookupQuery} ({@link QueryBuilder}).
 *
 * @param actors         {@code u:} player names and {@code #sources}, lower case
 * @param excludedActors {@code e:} entries that are not types, lower case
 * @param time           {@code t:}
 * @param radius         {@code r:}
 * @param actions        {@code a:}
 * @param includedTypes  {@code i:} namespaced keys
 * @param excludedTypes  {@code e:} entries that are types, namespaced keys
 * @param servers        {@code s:}
 * @param flags          {@code #preview}, {@code #count}
 */
public record QueryParameters(
        @NotNull Set<String> actors,
        @NotNull Set<String> excludedActors,
        @NotNull TimeSpec time,
        @NotNull RadiusSpec radius,
        @NotNull Set<Action> actions,
        @NotNull Set<String> includedTypes,
        @NotNull Set<String> excludedTypes,
        @NotNull ServerSpec servers,
        @NotNull Set<QueryFlag> flags) {

    /** No parameters at all. */
    public static final QueryParameters NONE = new QueryParameters(Set.of(), Set.of(), new TimeSpec.Any(),
            new RadiusSpec.Unspecified(), Set.of(), Set.of(), Set.of(), new ServerSpec.Local(), Set.of());

    /**
     * Copies the sets.
     *
     * @param actors         players and sources
     * @param excludedActors excluded players and sources
     * @param time           the time
     * @param radius         the radius
     * @param actions        the actions
     * @param includedTypes  included types
     * @param excludedTypes  excluded types
     * @param servers        the servers
     * @param flags          the flags
     */
    public QueryParameters {
        actors = Set.copyOf(actors);
        excludedActors = Set.copyOf(excludedActors);
        actions = Set.copyOf(actions);
        includedTypes = Set.copyOf(includedTypes);
        excludedTypes = Set.copyOf(excludedTypes);
        flags = Set.copyOf(flags);
    }

    /**
     * Whether a flag was given.
     *
     * @param flag the flag
     * @return {@code true} when present
     */
    public boolean has(@NotNull QueryFlag flag) {
        return flags.contains(flag);
    }

    /** {@code t:}: how far back. */
    public sealed interface TimeSpec {

        /** No {@code t:}: all time. */
        record Any() implements TimeSpec {
        }

        /**
         * {@code t:2h}: from that long ago until now.
         *
         * @param millis the age in milliseconds
         */
        record Within(long millis) implements TimeSpec {
        }

        /**
         * {@code t:1h-3d}: between two ages.
         *
         * @param newerMillis the smaller age (the window's end)
         * @param olderMillis the larger age (the window's start)
         */
        record Between(long newerMillis, long olderMillis) implements TimeSpec {
        }
    }

    /** {@code r:}: where. */
    public sealed interface RadiusSpec {

        /** No {@code r:}: the command's default. */
        record Unspecified() implements RadiusSpec {
        }

        /**
         * {@code r:10} or {@code r:10x5}: a box around the sender.
         *
         * @param horizontal the horizontal radius
         * @param vertical   the vertical radius, or -1 for the whole height
         */
        record Blocks(int horizontal, int vertical) implements RadiusSpec {
        }

        /**
         * {@code r:#world_nether}: one whole world.
         *
         * @param world the world name
         */
        record World(@NotNull String world) implements RadiusSpec {
        }

        /** {@code r:#global}: every world. */
        record Global() implements RadiusSpec {
        }
    }

    /** {@code s:}: which servers. */
    public sealed interface ServerSpec {

        /** No {@code s:}: this server. */
        record Local() implements ServerSpec {
        }

        /**
         * {@code s:survival,creative}.
         *
         * @param names lower-case server names
         */
        record Named(@NotNull Set<String> names) implements ServerSpec {

            /**
             * Copies the names.
             *
             * @param names lower-case server names
             */
            public Named {
                names = Set.copyOf(names);
            }
        }

        /** {@code s:#network}: every server. */
        record Network() implements ServerSpec {
        }
    }
}
