package gg.moonrise.hawkeye.core.service.query.syntax;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.core.service.query.catalog.FakeTypeCatalog;
import gg.moonrise.hawkeye.entity.action.Action;
import java.util.Set;
import org.junit.jupiter.api.Test;

public class QuerySyntaxTest {

    private final QuerySyntax syntax = new QuerySyntax(new FakeTypeCatalog());

    @Test
    public void readsEveryParameter() {
        QueryParameters p = parsed("u:Alex,#TNT t:2h r:10x5 a:-block,+container i:stone e:diamond_ore,Steve "
                + "s:Survival #preview");
        assertEquals(Set.of("alex", "#tnt"), p.actors());
        assertEquals(new QueryParameters.TimeSpec.Within(7_200_000), p.time());
        assertEquals(new QueryParameters.RadiusSpec.Blocks(10, 5), p.radius());
        assertEquals(Set.of(Action.BLOCK_BREAK, Action.CONTAINER_DEPOSIT), p.actions());
        assertEquals(Set.of("minecraft:stone"), p.includedTypes());
        assertEquals(Set.of("minecraft:diamond_ore"), p.excludedTypes());
        assertEquals(Set.of("steve"), p.excludedActors());
        assertEquals(new QueryParameters.ServerSpec.Named(Set.of("survival")), p.servers());
        assertTrue(p.has(QueryFlag.PREVIEW));
    }

    @Test
    public void readsLongPrefixesRangesAndSpecialRadii() {
        QueryParameters p = parsed("USER:alex time:1d-2h radius:#world_nether server:#network");
        assertEquals(new QueryParameters.TimeSpec.Between(7_200_000, 86_400_000), p.time());
        assertEquals(new QueryParameters.RadiusSpec.World("world_nether"), p.radius());
        assertEquals(new QueryParameters.ServerSpec.Network(), p.servers());
        assertEquals(new QueryParameters.RadiusSpec.Global(), parsed("r:#GLOBAL").radius());
    }

    @Test
    public void anEmptyListHasNoFilters() {
        assertEquals(QueryParameters.NONE, parsed("   "));
    }

    @Test
    public void namesTheFirstInvalidToken() {
        assertError("x:1", QueryError.Kind.UNKNOWN_PARAMETER, "x:1");
        assertError("hello", QueryError.Kind.UNKNOWN_PARAMETER, "hello");
        assertError("t:yesterday", QueryError.Kind.INVALID_TIME, "yesterday");
        assertError("r:far", QueryError.Kind.INVALID_RADIUS, "far");
        assertError("a:dance", QueryError.Kind.UNKNOWN_ACTION, "dance");
        assertError("i:stonee", QueryError.Kind.UNKNOWN_TYPE, "stonee");
        assertError("u:", QueryError.Kind.EMPTY_VALUE, "u:");
        assertError("s:Not!Valid", QueryError.Kind.INVALID_SERVER, "Not!Valid");
    }

    private QueryParameters parsed(String input) {
        return assertInstanceOf(ParseResult.Parsed.class, syntax.parse(input)).parameters();
    }

    private void assertError(String input, QueryError.Kind kind, String value) {
        ParseResult.Invalid invalid = assertInstanceOf(ParseResult.Invalid.class, syntax.parse(input), input);
        assertEquals(new QueryError(kind, value), invalid.error());
    }
}
