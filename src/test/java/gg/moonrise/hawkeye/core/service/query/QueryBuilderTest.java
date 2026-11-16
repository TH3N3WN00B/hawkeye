package gg.moonrise.hawkeye.core.service.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import gg.moonrise.hawkeye.entity.query.Area;
import gg.moonrise.hawkeye.entity.query.LookupQuery;
import gg.moonrise.hawkeye.entity.query.ServerScope;
import gg.moonrise.hawkeye.entity.query.TimeWindow;
import gg.moonrise.hawkeye.util.BlockPositions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

public class QueryBuilderTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final QueryContext.SenderPosition HERE = new QueryContext.SenderPosition("world", 100, 64, -50);

    private final QuerySyntax syntax = new QuerySyntax(new FakeTypeCatalog());
    private final QueryBuilder builder = new QueryBuilder(Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));

    @Test
    public void aRadiusIsABoxAroundTheSender() {
        LookupQuery query = built("r:10x3", player(0, 0));
        assertEquals(new Area.Box("world", 90, 61, -60, 110, 67, -40), query.area());
        LookupQuery fullHeight = built("r:5", player(0, 0));
        assertEquals(new Area.Box("world", 95, BlockPositions.MIN_Y, -55, 105, BlockPositions.MAX_Y, -45),
                fullHeight.area());
    }

    @Test
    public void theCommandDefaultAppliesWithoutARadius() {
        assertEquals(new Area.Box("world", 90, BlockPositions.MIN_Y, -60, 110, BlockPositions.MAX_Y, -40),
                built("u:alex", player(10, 0)).area());
        assertEquals(new Area.Everywhere(), built("u:alex", player(0, 0)).area());
        assertEquals(new Area.Everywhere(), built("u:alex", console()).area());
    }

    @Test
    public void agesBecomeATimeWindow() {
        assertEquals(new TimeWindow(NOW - 3_600_000, NOW), built("t:1h", console()).time());
        assertEquals(new TimeWindow(NOW - 7_200_000, NOW - 3_600_000), built("t:2h-1h", console()).time());
        assertEquals(TimeWindow.ALL, built("", console()).time());
    }

    @Test
    public void serversNeedThePermissionAndNoRadius() {
        assertEquals(new ServerScope.Network(), built("s:#network", network()).servers());
        assertError("s:#network", player(0, 0), QueryError.Kind.NETWORK_NOT_ALLOWED);
        assertError("s:creative r:10", network(), QueryError.Kind.AREA_WITH_SERVERS);
        assertEquals(new Area.Everywhere(), built("s:creative", new QueryContext(HERE, true, 10, 0, w -> true)).area());
    }

    @Test
    public void refusesWhatTheSenderCannotDo() {
        assertError("r:500", player(0, 100), QueryError.Kind.RADIUS_TOO_LARGE);
        assertError("r:5", console(), QueryError.Kind.RADIUS_NEEDS_POSITION);
        assertError("r:#nowhere", player(0, 0), QueryError.Kind.UNKNOWN_WORLD);
    }

    private static QueryContext player(int defaultRadius, int maxRadius) {
        return new QueryContext(HERE, false, defaultRadius, maxRadius, world -> world.equals("world"));
    }

    private static QueryContext console() {
        return new QueryContext(null, true, 0, 0, world -> true);
    }

    private static QueryContext network() {
        return new QueryContext(HERE, true, 0, 0, world -> true);
    }

    private LookupQuery built(String input, QueryContext context) {
        QueryParameters parameters = assertInstanceOf(ParseResult.Parsed.class, syntax.parse(input)).parameters();
        return assertInstanceOf(BuildResult.Built.class, builder.build(parameters, context)).query();
    }

    private void assertError(String input, QueryContext context, QueryError.Kind kind) {
        QueryParameters parameters = assertInstanceOf(ParseResult.Parsed.class, syntax.parse(input)).parameters();
        BuildResult.Invalid invalid = assertInstanceOf(BuildResult.Invalid.class, builder.build(parameters, context));
        assertEquals(kind, invalid.error().kind());
    }
}
