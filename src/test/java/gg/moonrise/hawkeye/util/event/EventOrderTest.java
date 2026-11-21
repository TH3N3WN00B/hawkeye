package gg.moonrise.hawkeye.util.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gg.moonrise.hawkeye.entity.action.Action;
import gg.moonrise.hawkeye.entity.action.ActionFamily;
import org.junit.jupiter.api.Test;

public class EventOrderTest {

    @Test
    public void messageAndSessionActionsAreOrderedByTimeAndEveryOtherActionByPlace() {
        for (Action action : Action.values()) {
            boolean aboutAPlayer = action.family() == ActionFamily.MESSAGE || action.family() == ActionFamily.SESSION;
            assertEquals(!aboutAPlayer, EventOrder.byPlace(action.code()), action.name());
        }
    }

    @Test
    public void theRuleCoversCodesNoActionUsesYet() {
        assertTrue(EventOrder.byPlace(0));
        assertTrue(EventOrder.byPlace(39));
        assertFalse(EventOrder.byPlace(40));
        assertFalse(EventOrder.byPlace(59));
        assertTrue(EventOrder.byPlace(60));
        assertTrue(EventOrder.byPlace(255));
    }
}
