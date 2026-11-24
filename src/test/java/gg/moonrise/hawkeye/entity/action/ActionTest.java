package gg.moonrise.hawkeye.entity.action;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.util.event.EventOrder;
import java.util.Optional;
import org.junit.jupiter.api.Test;

public class ActionTest {

    @Test
    public void everyCodeRoundTrips() {
        for (Action action : Action.values()) {
            assertEquals(Optional.of(action), Action.byCode(action.code()));
        }
        assertEquals(Optional.empty(), Action.byCode(63));
        assertEquals(Optional.empty(), Action.byCode(-1));
    }

    @Test
    public void storedCodesNeverChange() {
        assertEquals(1, Action.BLOCK_BREAK.code());
        assertEquals(2, Action.BLOCK_PLACE.code());
        assertEquals(10, Action.CONTAINER_DEPOSIT.code());
        assertEquals(30, Action.ENTITY_KILL.code());
        assertEquals(41, Action.COMMAND.code());
        assertEquals(51, Action.QUIT.code());
        assertEquals(52, Action.SNAPSHOT_JOIN.code());
        assertEquals(53, Action.SNAPSHOT_QUIT.code());
        assertEquals(54, Action.SNAPSHOT_TELEPORT.code());
        assertEquals(55, Action.SNAPSHOT_DEATH.code());
    }

    @Test
    public void snapshotsAreAboutAPlayerAndNeverRolledBack() {
        assertArrayEquals(new Action[] {Action.SNAPSHOT_JOIN, Action.SNAPSHOT_QUIT, Action.SNAPSHOT_TELEPORT,
                Action.SNAPSHOT_DEATH}, Action.of(ActionFamily.SNAPSHOT));
        assertFalse(ActionFamily.SNAPSHOT.hasSubject());
        for (Action action : Action.of(ActionFamily.SNAPSHOT)) {
            assertFalse(action.reversible(), action.name());
            assertFalse(EventOrder.byPlace(action.code()), action.name());
        }
    }

    @Test
    public void virtualContainerActionsAreContainerActionsThatAreNeverRolledBack() {
        assertEquals(12, Action.VIRTUAL_DEPOSIT.code());
        assertEquals(13, Action.VIRTUAL_WITHDRAW.code());
        assertArrayEquals(new Action[] {Action.CONTAINER_DEPOSIT, Action.CONTAINER_WITHDRAW, Action.VIRTUAL_DEPOSIT,
                Action.VIRTUAL_WITHDRAW}, Action.of(ActionFamily.CONTAINER));
        assertTrue(Action.CONTAINER_DEPOSIT.reversible() && Action.CONTAINER_WITHDRAW.reversible());
        for (Action action : new Action[] {Action.VIRTUAL_DEPOSIT, Action.VIRTUAL_WITHDRAW}) {
            assertFalse(action.reversible(), action.name());
            assertTrue(EventOrder.byPlace(action.code()), action.name());
        }
    }

    @Test
    public void familiesSayWhatTheSubjectIs() {
        assertTrue(ActionFamily.BLOCK.hasSubject());
        assertTrue(!ActionFamily.MESSAGE.hasSubject());
        assertEquals(6, Action.of(ActionFamily.BLOCK).length);
        assertTrue(!Action.BLOCK_GROW.reversible(), "growth is never rolled back");
        assertTrue(Action.ENTITY_SPAWN.reversible() && Action.ENTITY_REMOVE.reversible());
    }
}
