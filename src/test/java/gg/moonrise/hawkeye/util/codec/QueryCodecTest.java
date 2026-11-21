package gg.moonrise.hawkeye.util.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.query.Area;
import gg.moonrise.hawkeye.entity.query.LookupQuery;
import gg.moonrise.hawkeye.entity.query.RollbackState;
import gg.moonrise.hawkeye.entity.query.ServerScope;
import gg.moonrise.hawkeye.entity.query.TimeWindow;
import gg.moonrise.hawkeye.util.codec.binary.CodecException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

public class QueryCodecTest {

    @Test
    public void roundTripsEveryAreaAndFilter() {
        for (Area area : List.of(new Area.Everywhere(), new Area.World("my world:1,2"),
                new Area.Box("w", -10, -64, -5, 10, 320, 5), new Area.Block("w", 1, 2, 3))) {
            LookupQuery query = new LookupQuery(new ServerScope.Network(), area, new TimeWindow(1000, 2000),
                    Set.of("alex", "#tnt"), Set.of("steve"), Set.of(Action.BLOCK_BREAK, Action.CONTAINER_DEPOSIT),
                    Set.of("minecraft:diamond_ore"), Set.of("minecraft:stone"), RollbackState.ACTIVE);
            LookupQuery decoded = QueryCodec.decode(QueryCodec.encode(query));
            assertEquals(new ServerScope.Local(), decoded.servers(), "a job always runs locally");
            assertEquals(area, decoded.area());
            assertEquals(query.time(), decoded.time());
            assertEquals(query.actors(), decoded.actors());
            assertEquals(query.excludedActors(), decoded.excludedActors());
            assertEquals(query.actions(), decoded.actions());
            assertEquals(query.includedTypes(), decoded.includedTypes());
            assertEquals(query.excludedTypes(), decoded.excludedTypes());
            assertEquals(RollbackState.ACTIVE, decoded.rollbackState());
        }
    }

    @Test
    public void roundTripsAnOpenWindowAndEmptyFilters() {
        LookupQuery query = LookupQuery.at(new Area.Everywhere());
        assertEquals(query, QueryCodec.decode(QueryCodec.encode(query)));
    }

    @Test
    public void rejectsOtherVersionsAndGarbage() {
        assertThrows(CodecException.class, () -> QueryCodec.decode("v=9"));
        assertThrows(CodecException.class, () -> QueryCodec.decode("v=1\nfrom=x"));
    }
}
