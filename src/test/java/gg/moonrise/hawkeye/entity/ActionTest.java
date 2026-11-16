package gg.moonrise.hawkeye.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.action.ActionFamily;
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
    }

    @Test
    public void familiesSayWhatTheSubjectIs() {
        assertTrue(ActionFamily.BLOCK.hasSubject());
        assertTrue(!ActionFamily.MESSAGE.hasSubject());
        assertEquals(5, Action.of(ActionFamily.BLOCK).length);
    }
}
