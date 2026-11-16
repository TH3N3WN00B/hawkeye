package gg.moonrise.hawkeye.core.service.query;

import gg.moonrise.hawkeye.entity.query.Area;
import gg.moonrise.hawkeye.entity.query.LookupQuery;
import gg.moonrise.hawkeye.entity.query.RollbackState;
import gg.moonrise.hawkeye.entity.query.ServerScope;
import gg.moonrise.hawkeye.entity.query.TimeWindow;
import gg.moonrise.hawkeye.util.BlockPositions;
import gg.moonrise.moss.spring.SpringComponent;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

/**
 * Turns parsed parameters into a {@link LookupQuery} for one sender: a radius becomes a box around
 * them, ages become a time window, {@code s:} is checked against their permission, and the command's
 * default area applies when {@code r:} is missing.
 */
@SpringComponent
@RequiredArgsConstructor
public final class QueryBuilder {

    private final @NotNull Clock clock;

    /**
     * Builds a query.
     *
     * @param parameters the parsed parameters
     * @param context    the sender and the command's defaults
     * @return the query, or why it cannot be built
     */
    public @NotNull BuildResult build(@NotNull QueryParameters parameters, @NotNull QueryContext context) {
        ServerScope servers = switch (parameters.servers()) {
            case QueryParameters.ServerSpec.Local local -> new ServerScope.Local();
            case QueryParameters.ServerSpec.Named named -> new ServerScope.Named(named.names());
            case QueryParameters.ServerSpec.Network network -> new ServerScope.Network();
        };
        if (!(servers instanceof ServerScope.Local) && !context.networkAllowed()) {
            return invalid(QueryError.Kind.NETWORK_NOT_ALLOWED, "s:");
        }
        QueryParameters.RadiusSpec radius = parameters.radius();
        if (radius instanceof QueryParameters.RadiusSpec.Unspecified) {
            radius = context.defaultRadius() > 0 && servers instanceof ServerScope.Local
                    ? new QueryParameters.RadiusSpec.Blocks(context.defaultRadius(), -1)
                    : new QueryParameters.RadiusSpec.Global();
        }
        Area area;
        switch (radius) {
            case QueryParameters.RadiusSpec.Blocks blocks -> {
                if (!(servers instanceof ServerScope.Local)) {
                    return invalid(QueryError.Kind.AREA_WITH_SERVERS, "r:" + blocks.horizontal());
                }
                QueryContext.SenderPosition position = context.position();
                if (position == null) {
                    return invalid(QueryError.Kind.RADIUS_NEEDS_POSITION, "r:" + blocks.horizontal());
                }
                int limit = context.maxRadius();
                if (limit > 0 && (blocks.horizontal() > limit || blocks.vertical() > limit)) {
                    return invalid(QueryError.Kind.RADIUS_TOO_LARGE, Integer.toString(limit));
                }
                area = box(position, blocks);
            }
            case QueryParameters.RadiusSpec.World world -> {
                if (servers instanceof ServerScope.Local && !context.worldExists().test(world.world())) {
                    return invalid(QueryError.Kind.UNKNOWN_WORLD, world.world());
                }
                area = new Area.World(world.world());
            }
            case QueryParameters.RadiusSpec.Global global -> area = new Area.Everywhere();
            case QueryParameters.RadiusSpec.Unspecified unspecified -> throw new IllegalStateException("resolved above");
        }
        long now = clock.millis();
        TimeWindow time = switch (parameters.time()) {
            case QueryParameters.TimeSpec.Any any -> TimeWindow.ALL;
            case QueryParameters.TimeSpec.Within within -> TimeWindow.last(now, within.millis());
            case QueryParameters.TimeSpec.Between between ->
                    new TimeWindow(now - between.olderMillis(), now - between.newerMillis());
        };
        LookupQuery query = new LookupQuery(servers, area, time, parameters.actors(), parameters.excludedActors(),
                parameters.actions(), parameters.includedTypes(), parameters.excludedTypes(), RollbackState.ANY);
        return new BuildResult.Built(query, parameters);
    }

    private static @NotNull Area.Box box(@NotNull QueryContext.SenderPosition position,
            @NotNull QueryParameters.RadiusSpec.Blocks blocks) {
        int r = blocks.horizontal();
        int minY = blocks.vertical() < 0 ? BlockPositions.MIN_Y : position.y() - blocks.vertical();
        int maxY = blocks.vertical() < 0 ? BlockPositions.MAX_Y : position.y() + blocks.vertical();
        return new Area.Box(position.world(), position.x() - r, minY, position.z() - r, position.x() + r, maxY,
                position.z() + r);
    }

    private static @NotNull BuildResult invalid(@NotNull QueryError.Kind kind, @NotNull String value) {
        return new BuildResult.Invalid(new QueryError(kind, value));
    }
}
