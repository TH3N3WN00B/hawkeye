package gg.moonrise.hawkeye.core.service.lookup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.actor.Actor;
import gg.moonrise.hawkeye.entity.event.StoredEvent;
import gg.moonrise.hawkeye.util.BlockPositions;
import gg.moonrise.hawkeye.util.EventIds;
import java.util.List;
import org.junit.jupiter.api.Test;

public class LookupMergeTest {

    private static final long NOW = 1_780_000_000_000L;

    @Test
    public void pagesOfSeveralShardsMergeNewestFirst() {
        List<ShardResult<List<StoredEvent>>> results = List.of(
                new ShardResult.Read<>("home", List.of(event(1, 1), event(4, 1), event(7, 1))),
                new ShardResult.Read<>("east", List.of(event(2, 2), event(3, 2), event(8, 2))));

        LookupOutcome.Page page = assertInstanceOf(LookupOutcome.Page.class, LookupMerge.page(results, 4));

        assertEquals(List.of(1L, 2L, 3L, 4L), page.events().stream().map(LookupMergeTest::age).toList());
        assertTrue(page.hasMore());
        assertEquals(page.events().getLast().id(), page.nextCursor());
        assertTrue(page.warnings().isEmpty());
    }

    @Test
    public void theExtraRowDecidesWhetherMoreFollow() {
        List<ShardResult<List<StoredEvent>>> results = List.of(
                new ShardResult.Read<>("home", List.of(event(4, 1), event(5, 1))));

        LookupOutcome.Page page = assertInstanceOf(LookupOutcome.Page.class, LookupMerge.page(results, 2));

        assertEquals(2, page.events().size());
        assertFalse(page.hasMore());
    }

    @Test
    public void aFailedShardIsAWarningNextToTheOthers() {
        List<ShardResult<List<StoredEvent>>> results = List.of(
                new ShardResult.Read<>("home", List.of(event(5, 1))),
                new ShardResult.Unreadable<>("east", "connection refused"));

        LookupOutcome.Page page = assertInstanceOf(LookupOutcome.Page.class, LookupMerge.page(results, 8));

        assertEquals(1, page.events().size());
        assertEquals(List.of(new LookupWarning("east", "connection refused")), page.warnings());
    }

    @Test
    public void everyShardFailingFailsTheLookup() {
        LookupOutcome.Failed single = assertInstanceOf(LookupOutcome.Failed.class, LookupMerge.page(List.of(
                new ShardResult.Unreadable<>("home", "disk full")), 8));
        assertEquals("disk full", single.reason());

        LookupOutcome.Failed several = assertInstanceOf(LookupOutcome.Failed.class, LookupMerge.count(List.of(
                new ShardResult.Unreadable<>("home", "a"), new ShardResult.Unreadable<>("east", "b"))));
        assertEquals("home: a; east: b", several.reason());
    }

    @Test
    public void noTargetsIsAnEmptyResult() {
        LookupOutcome.Page page = assertInstanceOf(LookupOutcome.Page.class, LookupMerge.page(List.of(), 8));
        assertTrue(page.events().isEmpty());
        assertEquals(0, page.nextCursor());
        assertEquals(new LookupOutcome.Count(0, List.of()), LookupMerge.count(List.of()));
    }

    @Test
    public void countsAddUpAndKeepWarnings() {
        LookupOutcome.Count count = assertInstanceOf(LookupOutcome.Count.class, LookupMerge.count(List.of(
                new ShardResult.Read<>("home", 12L), new ShardResult.Read<>("east", 30L),
                new ShardResult.Unreadable<>("west", "timeout"))));

        assertEquals(42, count.total());
        assertEquals(List.of(new LookupWarning("west", "timeout")), count.warnings());
    }

    /** An event {@code age} seconds old, recorded by {@code node}. */
    private static StoredEvent event(long age, int node) {
        return new StoredEvent(EventIds.compose(NOW - age * 1000, node, 0), "server", "world",
                BlockPositions.pack(0, 64, 0), Actor.source("tnt"), Action.BLOCK_BREAK, "minecraft:stone",
                "minecraft:air", 0, false, null);
    }

    private static long age(StoredEvent event) {
        return (NOW - event.epochMillis()) / 1000;
    }
}
