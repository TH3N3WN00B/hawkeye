package gg.moonrise.hawkeye.core.service.importer.mapping;

import gg.moonrise.hawkeye.entity.event.PendingEvent;
import gg.moonrise.hawkeye.entity.importer.SkipReason;
import org.jetbrains.annotations.NotNull;

/** What one CoreProtect row becomes. */
public sealed interface MappedRow {

    /**
     * A Hawkeye event, with a capture stamp at the row's second (the import's id assigner numbers it).
     *
     * @param event the event
     */
    record Mapped(@NotNull PendingEvent event) implements MappedRow {
    }

    /**
     * No event; counted under a reason.
     *
     * @param reason why
     */
    record Skipped(@NotNull SkipReason reason) implements MappedRow {
    }
}
